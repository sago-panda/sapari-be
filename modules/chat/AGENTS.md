# chat — live chat domain

Root `AGENTS.md` owns the cross-cutting rules; this file is chat-specific only.
✅ connect / send / fan-out / room-end / kick + ban escalation (REST host: **live-app**).
⬜ history (VOD). Run `./gradlew :modules:chat:chat-core:test`.

## One module, two stacks — the rule that governs everything else

`chat-core` is consumed by **streaming-app (WebFlux, no relational DB, no blocking Mongo)** and by
**live-app (MVC)**, which hosts the kick REST endpoint. Both stacks land on the classpath of both apps.

- **Reactive adapters carry `@Repository`** and are component-scanned.
- **Blocking adapters carry no stereotype at all** (`ChatKickLogRepositoryImpl`,
  `ChatKickWriteRedisRepository`, `ChatKickEventRedisPublisher`, `ChatMessageEvidenceMongoRepository`).
  Scanning them creates beans in the reactive app whose dependencies do not exist there. The host app that
  owns the stack registers them with `@Bean`. **Do not "fix" this by adding an exclude list to
  streaming-app** — every new adapter would be a place to forget.
- Auto-configuration ignores stereotypes: `DataSourceAutoConfiguration` fires on classpath presence alone
  and broke streaming-app's boot once. It is cut by `excludeName` (string, because the class is
  runtime-only there) and `AutoConfigurationExclusionTest` guards the spelling.
- **The MVC host is live-app, and that is forced, not chosen.** Any app carrying `live-core` (needed for the
  `GetLiveRoomUseCase` bean) also scans `RoomTokenConfig`, whose `@Validated` properties require live's RS256
  **private signing key** and LiveKit credentials — so any other host would have to duplicate those secrets.
  There is no inter-app HTTP anywhere in this repo to route around it.
- **The two apps exclude each other's half in opposite ways.** streaming-app scans `com.sapari.chat` and
  relies on blocking adapters having no stereotype; live-app cannot do the mirror of that (the reactive
  adapters *do* carry `@Repository`), so it drops `com.sapari.chat` from its component scan **by package
  regex** and registers the blocking beans with `@Bean`. Excluding by class would rot — a new reactive
  adapter would silently boot there, and one of them connects to Redis in its constructor.
  `ChatScanExclusionTest` guards the filter without booting a context.
- ⚠️ **The exclusion also drops `ChatMongoConfig`, which is the only place UUIDs are pinned to standard BSON
  binary.** The writing side (streaming-app) still pins it, so data stays correct and only the *reading* side
  breaks — evidence documents come back with `org.bson.types.Binary` where a UUID belongs, and there is no
  converter for that. live-app re-registers the customizer itself. Anything else that config provides has to
  be re-provided the same way.
- `mongodb-driver-sync` is **testImplementation only** — the blocking adapter compiles against
  `MongoTemplate` without it, and promoting it would hand the reactive app a sync driver.

ArchUnit enforces the calling direction: streaming-app must not touch blocking Redis/Mongo templates or
blocking chat use cases; live-app (MVC) must not touch reactive ones. **Rule ② matches class names by regex** —
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
original in plaintext because a privileged reader may be on a different pod. The masked view is the default
and the privileged one is the exception — **never invert that**, or every new role leaks by omission.

Privileged means **room owner or ADMIN**, and those are two different axes on purpose. Ownership is
per-room: a SELLER visiting someone else's broadcast is a viewer and sees masked text — that split is why
gating moved off `role == SELLER` in the first place. ADMIN is account-level and ignores ownership, which is
what the permission model always said (`f(role, isRoomOwner)`); fan-out had only ever implemented the
ownership half. Moderators need the unmasked text for the same reason owners do — you cannot judge what to
kick without seeing what was said.

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
⚠️ **That expiry is the only path that ever puts a TTL on `chat:kicked:`, and it hangs entirely on receiving
`RoomEnded` — a Pub/Sub message with no persistence.** Lose one and that room's kick set stays forever.
This mattered less while the write path was broken and the set was never created; now that kicks land, every
lost end signal leaks a key. Room ids are UUIDs so nothing malfunctions — it is pure leak, and it belongs to
live's delivery guarantee, not chat's.

⚠️ **The signal can also arrive and then be undone.** Room-end sets the 24h expiry; a kick that lands in that
window runs `SADD` + `PERSIST` and strips it, and the two callers sit on different pods so nothing orders
them. `PERSIST` is not removable — it exists because `SADD` inherits a leftover expiry and the whole list
would vanish silently. Two purposes push the same key in opposite directions, which is why recovery for this
key belongs in one place rather than being patched at either end.

## Kick & ban — every input is server-read

Evidence is fetched **by the server** from `messageId`; the command never carries the text (the kicker
would author their own evidence). `ChatKickLog.from` is the only place room·author consistency is checked.

**No input to the permission decision comes from the request body.** Room owner and liveness come from
`live-api`; the kicker's role from the authenticated principal; the target's role from the **evidence
message** (`senderRole`, captured at send time from the live-signed room token). That last one is why this
path does not depend on `user-api`: asking the account store would hand the host app `UserAccountUseCase`,
which also carries withdrawal and nickname changes. The cost is that a user promoted after writing the
message is judged by the old role — acceptable, since the message is what is being judged.

**Order is security, not taste.** Permission before evidence lookup (otherwise the endpoint reports whether
a `messageId` exists), and liveness after permission (otherwise it reports whether a room is live). Unknown
room and unknown user both collapse into permission-denied for the same reason.

Idempotency is the DB constraint (`UNIQUE(user_id, live_room_id)` + `ON CONFLICT DO NOTHING`); the affected
row count is the **ban-escalation trigger and nothing else** — treating `false` as an early return means a
user whose enforcement cache was lost can never be kicked in that room again.

**DB-first**: the log commit is confirmed before Redis and publish. **What that accepts:** a crash between
the commit and `register` leaves an audit row and possibly a ban with no enforcement — the kicked user is
still in the room. There is no compensating job; the recovery path is the moderator kicking again, and that
is safe because every step is idempotent. Saying so out loud matters: silence here reads as "this cannot
happen" rather than "we chose to let it." Registration is `SADD` + `PERSIST` in one
Lua call because `SADD` inherits a leftover expiry and the whole list would then vanish silently.

⚠️ **Both DB writes are `@Modifying` native INSERTs, and Spring Data gives those no ambient transaction.**
Called without one they fail with `No active transaction for update or delete query` — measured, and it made
every kick a 500 until `ChatKickRecorder` was introduced. That bean exists solely to hold the boundary around
the two DB writes while leaving Redis outside it; it must stay a **separate bean** because a same-class call
skips the proxy and the annotation silently does nothing. live's `RtmpIngressAssigner` is the same pattern.
**Unit tests cannot see this** — mocks never run the query and `@DataJpaTest` opens a transaction for you.
`ChatKickRecorderTest` calls with `Propagation.NOT_SUPPORTED` to remove that help.

Escalation counts kicks **across rooms** on a 2-year window; per-seller counting lets a user who rotates
rooms reach no threshold at all. Thresholds are read as **at-or-above**, not exact — the design doc's table
gives the same answer while kicks arrive one at a time, and differs only where the table is silent (window
shrink, a concurrent kick skipping a threshold). **Automatic escalation stops at one year.** The doc's
12-kick permanent ban was moved to a human's hands: nothing reversible-only-by-hand should be applied by a
server that has no code to reverse it, and that matches every other call this domain has made (the kick set
expires rather than being deleted; a corrupted key is not self-healed). Permanent bans still exist as rows
with a null expiry — an admin puts them there. An active ban is **mirrored, never stacked**,
and the longest-lived one wins — picking a shorter row releases the mirror before the record. A duplicate
kick does **not** re-count: it would let a seller extend a ban indefinitely by re-kicking. The cost is a hole
— if the log commits and the ban write fails, the retry takes the duplicate path and skips escalation.

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
- **A test that supplies wiring the app does not is worse than no test** — it goes green while production
  breaks. Both of this branch's runtime failures hid behind exactly that (`@DataJpaTest`'s transaction, a
  test-local UUID customizer). `ChatModerationWiringTest` boots the real live-app context and asserts on the
  beans it actually built; it supplies its own properties so CI runs it rather than skipping it by tag.
- **A test claiming a guarantee must be mutation-checked**: revert the production line, confirm that test —
  and ideally only that test — fails, restore. `checkOrphanJavadoc` gates javadoc placement in `check`.
