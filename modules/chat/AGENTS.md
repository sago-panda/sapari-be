# chat — live chat domain

Root `AGENTS.md` owns the cross-cutting rules; this file is chat-specific only.
✅ connect / send / fan-out / room-end / ban gate. 🚧 kick (every piece exists, nothing wires them).
⬜ history (VOD). Run `./gradlew :modules:chat:chat-core:test`.

## One module, two stacks — the rule that governs everything else

`chat-core` is consumed by **streaming-app (WebFlux, no relational DB, no blocking Mongo)** and by
**api-app (MVC)**. Both stacks therefore land on the classpath of both apps.

- **Reactive adapters carry `@Repository`** and are component-scanned.
- **Blocking adapters carry no stereotype at all** (`ChatKickLogRepositoryImpl`,
  `ChatKickWriteRedisRepository`, `ChatKickEventRedisPublisher`, `ChatMessageEvidenceMongoRepository`).
  Scanning them creates beans in the reactive app whose dependencies do not exist there. The host app that
  owns the stack registers them with `@Bean`. **Do not "fix" this by adding an exclude list to
  streaming-app** — every new adapter would be a place to forget.
- Auto-configuration ignores stereotypes: `DataSourceAutoConfiguration` fires on classpath presence alone
  and broke streaming-app's boot once. It is cut by `excludeName` (string, because the class is
  runtime-only there) and `AutoConfigurationExclusionTest` guards the spelling.
- `mongodb-driver-sync` is **testImplementation only** — the blocking adapter compiles against
  `MongoTemplate` without it, and promoting it would hand the reactive app a sync driver.

ArchUnit enforces the calling direction: streaming-app must not touch blocking Redis/Mongo templates or
blocking chat use cases; api-app must not touch reactive ones. **Rule ② matches class names by regex** —
name a service something other than `KickUserService` and it silently guards nothing.

## Failure policy — everything opens, nothing closes

Every moderation read is **fail-open**. There is no fail-closed path in chat.

| Read | On failure | Loses |
|---|---|---|
| room-ended marker (entry + send re-check) | allow | ended-room blocking |
| kick set | allow | kick enforcement |
| ban key | allow | ban enforcement |
| rate limit | allow | flood control |
| session HASH register / viewer count | allow | count accuracy only |

The bet: total chat outage is worse than a kicked user slipping through. **Adapters propagate, consumers
decide** — an adapter that swallows into `false` destroys the distinction between "definitely not kicked"
and "could not ask".

**One exception, and it changes severity, not policy.** A kick key of the wrong Redis type keeps failing
forever — retry is idempotent *and* permanently futile. `KickStoreCorruptedException` splits it so the
"never heals" case is not buried in the logs of an outage that will. Detection is by message prefix
(`RedisWrongType`), because the driver uses one exception type for every server-side error; measured, not
assumed. **No self-healing DEL** — that would erase the room's whole kick list and possibly someone else's data.

Throttled logs are **interval-based, never count-based**: a count threshold silences short bursts after the
first, which is backwards. Reasons are counted separately so one does not bury another's first occurrence.
**Never let a metric or audit call throw inside a fail-open handler** — the policy inverts.

## Redis keys — one source, and it is package-private

`ChatRedisKeys` (package-private, `infrastructure/redis`) owns every key string. Blocking and reactive
adapters share it by living in the same package. **Do not widen it to `public`.**

| Key | Type | Notes |
|---|---|---|
| `chat:pubsub:{roomId}` | Pub/Sub | `PUBSUB_PREFIX` is shared by publish, pattern-subscribe and channel parsing |
| `chat:room:{id}:sessions` | HASH | 24h TTL is a backstop; expiry means *no new joins* for 24h, not a long broadcast |
| `chat:room:{id}:ended` | String | 30m — floor = longest room token, ceiling = blast radius of a false end signal |
| `chat:kicked:{roomId}` | SET | **no TTL while live**; room-end attaches 24h. `SADD` must be followed by `PERSIST` |
| `chat:banned:{userId}` | String | key presence = banned; TTL is the expiry. No status field, so no stale window |
| `ratelimit:chat:{userId}` | String | prefix position differs; ownership is already in the name |

`live:room:ended` is **live's channel** — subscribed, never written, and deliberately not in `ChatRedisKeys`.

Only the kick SET is exposed to WRONGTYPE (`SISMEMBER` type-checks). `SET`/`EXISTS` keys are not, so the
corruption split is not widened to them.

## Wire contracts — three, and all three fail silently when broken

1. **`ChatEnvelope`** (`application/protocol`, not infrastructure — the port returns it). Both the reactive
   broadcaster and the blocking kick publisher serialize the **real type**; hand-built JSON was the old
   design and a one-character field-name typo compiles fine and vanishes across every pod.
2. **`ChatMessageTypeMixin` registration is mandatory** on any mapper touching a CHAT envelope. Omission
   dies loudly on first deserialize — that is the good case.
3. **`OutboundMessage` omits null fields** (`@JsonInclude(NON_NULL)`). It is an 8-type union, so over half
   is empty in any frame; the padding used to exceed the message body. Front-end contract: absent key, not
   `null` — `=== null` breaks, everything else does not.

**PII gating happens at fan-out, not on the wire.** The envelope carries `senderEmail` and the unmasked
original in plaintext because the room owner may be on a different pod. `toView()` is the default and
`toOwnerView()` the exception — never invert that.

## Send path — the order is the contract

`isRoomAlive` (memory) → validation → permission → kick (Redis) → rate limit (Redis) → profanity → Mongo
save → publish. Zero-cost checks first; reordering makes a rejected guest cost a Redis round-trip.

- **`concatMap`, never `flatMap`** on inbound frames — flatMap's 256 concurrency lets a pipelining client
  bypass the local rate-limit window entirely.
- **Persist before publish**, so `DuplicateKey` is caught before a duplicate broadcast.
- **Publish failure is absorbed**: saved + ACK'd is a success; cross-pod delivery is best-effort.
- Rate-limit exemption is `role + room ownership`, not role — a seller in someone else's room is a viewer.
- `clientMsgId` is **required**; the dedup index is partial (`$type:"string"`), so a missing value silently
  disables idempotency for that message. Its length cap must match on both the rejecting and the echoing
  side (`ChatConstants`), or a frame is rejected while its echo is truncated.

## Sessions & shutdown (`ChatSessionRegistry`, streaming-app)

Outbound buffer is **256 and bounded**. Raising it is the documented path to node death.

- **Buffer overflow and emit contention are different failures.** Overflow means the client cannot keep up →
  close. Contention means another thread held the lock → drop that message, keep the session. Merging them
  makes load shed healthy viewers, whose reconnects raise load further.
- Close codes: **1000** room ended (normal *and* the late-discovery path — identical on purpose),
  **1008** entry denial / kick, **1013** buffer overflow. 1013 rather than 1000 exists to stop instant reconnect.
- Terminate signals **both** channels: sink `complete` (drains the buffer so a just-sent SYSTEM arrives) and
  a control sink (the escape when the client never reads). First reason wins; a later kick cannot overwrite it.
- Fan-out budgets are **per operation, not per session** — the loop runs on the pod's shared Redis subscriber
  thread.
- A close frame that never flushes leaves the channel open; `NettyConnectionRegistry` reclaims it after 5s.
  Server idle-timeout does not substitute (measured: it does not apply post-upgrade, and it would also cut
  silent viewers).

## Room end — the signal is lossy, so it is checked three times

`live:room:ended` is Pub/Sub with no persistence: a pod whose subscription was down misses it entirely, and
its sessions keep writing to a finished room.

1. Send path re-checks the marker on a **30s window**, and **stops asking once ended** — a window alone only
   blocks one frame per interval.
2. On discovery it closes **that session only** (1000, same frames as the normal path). Room-wide closing
   would mean duplicating the 5-step handler in transport.
3. Session HASH TTL is the last backstop.

The 5-step handler writes the marker **first** (order sets the size of the re-entry hole), and each step is
independent — a failed notification must not prevent sessions from closing. Log level is chosen by **what
was lost** (control → ERROR, self-healing cleanup → WARN), not by exception type. The kick set is
**expired, not deleted**: the waking signal cannot be verified, and deletion would be irreversible.

## Kick & ban — pieces are done, wiring is not

Evidence is fetched **by the server** from `messageId`; the command never carries the text (the kicker
would author their own evidence). `ChatKickLog.from` is the only place room·author consistency is checked.
Idempotency is the DB constraint (`UNIQUE(user_id, live_room_id)` + `ON CONFLICT DO NOTHING`); the affected
row count is the **ban-escalation trigger and nothing else** — treating `false` as an early return means a
user whose enforcement cache was lost can never be kicked in that room again.

**DB-first**: the log commit is confirmed before Redis and publish. Registration is `SADD` + `PERSIST` in one
Lua call because `SADD` inherits a leftover expiry and the whole list would then vanish silently.

Gates left for the wiring commit: **room must be LIVE** (rejects the late registration that leaks a key),
kicker↔room authorization from the authenticated principal (`kickerRole` in the command is client input),
api-app `@EntityScan` + `mongodb-driver-sync` runtime + four `@Bean` registrations.

## Tests

Boot 4 split the slices into modules and **has no Mongo slice** (verified by jar inspection):
`spring-boot-data-jpa-test`, `spring-boot-jdbc-test`; `MongoProperties` binds to **`spring.mongodb`**, not
`spring.data.mongodb` (removed in 4.0.0 — a wrong prefix binds nothing and falls back to localhost).
Prefer `@ServiceConnection` over naming properties for exactly that reason.

- `ChatCoreTestApplication` decomposes `@SpringBootApplication` to keep a single scan, which loses
  `TypeExcludeFilter` — it is re-added explicitly. **Without it every slice silently runs a full scan.**
- Any `@SpringBootTest` on chat-core boots `RedisChatBroadcaster`, which connects in its constructor
  (`autoConnect(0)`, deliberate — it removes the pre-subscribe loss race). **Such tests need a Redis container**
  even when they test something else.
- Schema for JPA tests is applied from the **real Flyway file**; a second copy drifts while staying green.
- **A test claiming a guarantee must be mutation-checked**: revert the production line, confirm that test —
  and ideally only that test — fails, restore. `checkOrphanJavadoc` gates javadoc placement in `check`.
