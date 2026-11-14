# chat — live chat domain

Root `AGENTS.md` owns the cross-cutting rules; this file is chat-specific only.
✅ connect / send / fan-out / room-end / kick + ban escalation / history (VOD) / reverse paths —
ban release, kick-list reconcile, ban-mirror reconcile, end-marker clear, dictionary propagation + admin API
(REST host: **live-app**). Run `./gradlew :modules:chat:chat-core:test`.

Each line states a rule and names the class that enforces it; the detail, the measurements and their history
live in that class's javadoc. If a line here and the class comment disagree, re-measure.

## One module, two stacks — the rule that governs everything else

`chat-core` is consumed by **streaming-app (WebFlux, no relational DB, no blocking Mongo)** and by
**live-app (MVC)**. Both stacks land on the classpath of both apps.

- **Reactive adapters carry `@Repository`; blocking adapters carry no stereotype** (`ChatKickLogRepositoryImpl`,
  `ChatKickWriteRedisRepository`, `ChatKickEventRedisPublisher`, `ChatMessageEvidenceMongoRepository`) and the
  host app registers them with `@Bean`. **Do not "fix" this with an exclude list in streaming-app.**
- streaming-app cuts `DataSourceAutoConfiguration` by `excludeName` — `AutoConfigurationExclusionTest`.
- **The MVC host is live-app, and that is forced** — any app carrying `live-core` scans `RoomTokenConfig`,
  which needs live's private signing key and LiveKit credentials.
- live-app drops `com.sapari.chat` from its scan **by package regex**, never by class — `ChatScanExclusionTest`.
- ⚠️ That exclusion also drops `ChatMongoConfig`, the only place UUIDs are pinned to standard BSON binary;
  live-app re-registers the customizer. Anything else that config provides must be re-provided the same way.
- `mongodb-driver-sync` is **testImplementation only** — promoting it hands the reactive app a sync driver.
- Every chat port carries exactly one of `@BlockingChat` / `@ReactiveChat` / `@InMemoryChat`, and any chat type
  holding a blocking template or a JPA repository must be `@BlockingChat` — ArchUnit
  (`every_chat_port_declares_whether_it_blocks`, `chat_types_holding_blocking_infrastructure_are_marked_blocking`).
  `ChatKickRecorder` and `ChatAccountEventHandler` are marked by hand; neither rule counts them.

## Failure policy — entry reads open, send reads refuse, the rate limit degrades

| Read | On failure | Loses |
|---|---|---|
| room-ended marker / kick set / ban key — **entry** | allow | that enforcement at entry |
| room-ended marker / kick set / ban key — **send** | **refuse** that send (`ERROR(UNAVAILABLE)`) | that one send |
| withdrawal record (entry only) | allow | re-entry block on a pre-withdrawal room token |
| rate limit | **degrade** to pod-local GCRA (`RedisRateLimiter`, same 3s / burst 3) | cross-pod accuracy (≈ N pods × allowance) |
| session HASH register / viewer count | allow | count accuracy only |

- **Why opposite directions:** delivery is Redis Pub/Sub only, so while these reads fail publish usually fails
  too — a let-through send reaches no one and costs an unchecked message in history. A refused send is refused
  **before persist**, so a same-`clientMsgId` retry cannot duplicate. Refusing entry closes 1008, which the
  front end treats as "do not reconnect", while an open entry gate only grants reading.
- The two send reads run in one `Mono.zip`: one failed read decides, so kick = true + ban read failed gives
  `UNAVAILABLE`, not `KICKED`, and no close. The send is still blocked.
- A round trip over the command bound (`RedisTimeoutConfig`, 200ms) is a failure — that is what makes the
  fallbacks fire under latency, not only under an outage.
- **Adapters propagate, consumers decide** — swallowing into `false` erases "not kicked" vs "could not ask".
- **Exception: a kick key of the wrong Redis type is let through at send** (`KickStoreCorruptedException`,
  detected by the `RedisWrongType` prefix). Retry never heals it and refusing would stop the room's chat until
  a human clears it. **No self-healing DEL.**
- Throttled logs are **interval-based, never count-based**; each reason has its own latch.
- **Never let a metric or audit call throw inside a fail-open handler** — the policy inverts. Absorbing a
  failure still owes a counter and an interval-throttled log (`RedisRateLimiter` is the worked example).
- **Never hand a record to a logger** — `ChatSession.toString()` prints `email`. Log scalars.
- `ChatMetrics` (`application/port`) speaks domain-event vocabulary; a new signal is a new method there.
  ArchUnit rule 16 bans the micrometer *library* in `application/`, not observation.

## Profanity dictionary

Every measured number and vector: `ProfanityFilter`'s class javadoc and the headers of `profanity-words.txt` /
`profanity-whitelist.txt`.

- **The table is the source of truth**: `live_schema.chat_filter_word` → Redis snapshot `chat:dict:snapshot` →
  in-pod trie. live-app writes (`ChatDictionaryBootstrap` seeds only an **empty** table, then publishes on
  every boot); streaming-app reads (`ProfanityDictionaryRefreshHandler`: boot + notify + poll in one
  `concatMap`). The **poll** is what makes propagation work — Pub/Sub keeps nothing.
- ⚠️ `V1__init_live.sql` calls tier 2 "Redis Pub/Sub" — imprecise (the content is a snapshot key; pub/sub
  carries only the version) and **checksum-locked**. This line is the correction; do not edit the migration.
- Version + `SET` + `PUBLISH` are **one Lua script** under a CAS; the new version is
  `max(base + 1, publish time ms)` so a lost-keys restart never reuses a number
  (`ProfanityDictionarySnapshotRedisPublisher`). Two predicates — readable (`readVersionToken`) and
  buildable-on (`isEligibleBase`) — keep everything the publisher writes readable by the fleet.
- The receiver applies a **regressed** version loudly and drops an **equal** one silently; that is the price
  of comparing versions only (`ProfanityDictionarySnapshotCodec`).
- A transport failure does **not** advance the local version; a content refusal does. A rejected swap keeps
  the previous filter — a propagated dictionary never fails boot.
- **Admin API `/api/v1/chat/filter-words`** (GET / POST / DELETE, all ADMIN, live-app) —
  `ChatFilterWordAuthorizationTest`. A write validates the **resulting whole set**, stores exactly the value it
  judged (`ProfanityFilterFactory.normalize`), runs under one advisory lock
  (`ChatFilterWordWriteRecorder.underWriteLock`) and attributes rejection: pre-change set already bad →
  `409 CHAT-011`, the submitted item → `400 CHAT-010`. Delete refuses only emptying the word set — removal
  cannot create a violation.
- `chat.profanity.words` / `.whitelist` **add to** the bundled list, are boot-only, and die at the first swap.
- **Missing resource, or zero words after comments, is a boot failure** — an empty dictionary is the filter
  silently off.
- **The trap is short entries.** Matching is substring with no word boundary, so a two-syllable entry that is
  also an ordinary morpheme censors normal speech. **Do not add an entry without the false-positive regression
  (`ProfanityDictionaryTest`) and a read of the crossing results** — a censored seller line is worse than a
  missed insult, because it cannot be recalled and the masked text is what gets persisted.
- A space is not protection — 2B joins adjacent tokens by design. Masking is span-scoped; filter covered spans
  **before** `mergeRanges`, never after.
- Whitelist rules (`ProfanityDictionaryTest`; enforced at load for both the bundled file and config): not a
  bare profanity and the residue must be a word worth keeping (refused); should contain a profanity (warning).
  At the **config** entrance a zero-width character is stripped with a warning; space-like ones refuse.
- **Open evasion axes, by decision:** Cyrillic/Latin homoglyphs, jamo spelled out, digit homoglyphs, an
  exhaustive 3-syllable sweep. `MAX_WORD_LENGTH = 16` bounds the 2B worst case — do not re-add a work budget.

## Redis keys — one source, and it is package-private

`ChatRedisKeys` (package-private, `infrastructure/redis`) owns every key string. **Do not widen it to `public`.**

| Key | Type | Notes |
|---|---|---|
| `chat:pubsub:{roomId}` | Pub/Sub | `PUBSUB_PREFIX` is shared by publish, pattern-subscribe and channel parsing |
| `chat:room:{id}:sessions` | HASH | `sessionId → userId:lastSeenMillis`; stale after 4 heartbeats; 24h key TTL backstop |
| `chat:room:{id}:ended` | String | 70m; floor = session `max-lifetime` (streaming-app refuses to boot otherwise) |
| `chat:kicked:{roomId}` | SET | **no TTL while live**; room-end attaches 24h; every `SADD` is followed by `PERSIST` |
| `chat:banned:{userId}` | String | presence = banned, TTL = expiry |
| `chat:ban:ver:{userId}` | String | highest change version that reached the mirror; writes at or below it are rejected |
| `chat:withdrawing:{userId}` | String | presence = withdrawal requested; read by `EntryGate`; TTL floor = room-token life (the adapter refuses below 300s). A future cancel path must delete it |
| `ratelimit:chat:{userId}` | String | — |
| `chat:account:events` | Pub/Sub | **outside `PUBSUB_PREFIX`** on purpose — the room pattern parses its suffix as a UUID |

`live:room:ended` (channel) and `live:room:ended:{roomId}` (durable key, read in one `EXISTS` with the chat
marker by `ChatRoomEndedRedisRepository.isEnded`) are **live's** — read, never written, not in `ChatRedisKeys`.
The string and the 70m TTL are copies of `LiveRedisKeys`; nothing checks them. **Raising `max-lifetime` needs
live told first.** That `EXISTS` is `CROSSSLOT` on a Redis cluster.

## Wire contracts

0. **The room token must carry `iat`** (`RoomTokenVerifier`; live sets it in `RoomTokenProvider.issue`). A
   missing `iat` is rejected loudly, by design.
1. **`ChatEnvelope`** is serialized as the real type by both publishers. Receivers disable
   `FAIL_ON_UNKNOWN_PROPERTIES`, so add fields **nullable and unvalidated**. An unknown `kind` still throws:
   **ship a new envelope kind to consumers first.** Envelope records validate their own required fields.
2. **`ChatMessageTypeMixin` registration is mandatory** on any mapper touching a CHAT envelope.
3. **`OutboundMessage` omits nulls** (`NON_NULL`) — front-end contract: absent key, not `null`.
4. **`ChatAccountEvent`** on `chat:account:events` is chat-internal; its receiver is lenient for the same
   rollout reason.

**PII gating happens at fan-out, not on the wire.** The masked view is the default and the privileged one is
the exception — **never invert it**. Privileged = room owner **or** ADMIN, decided in one function,
`ChatPermissionPolicy.seesUnmaskedContent(role, isRoomOwner)`, used by live fan-out, history and the admin
entry audit (`usesPrivilegedViewInSomeoneElsesRoom`; tripwire `PrivilegedEntryAuditTest`). Ownership is
per-room: a SELLER in someone else's room is a viewer.

**The sender's email is never persisted** (`ChatMessageDocument`, SPR-200). Messages live for the 2-year
TTL and a withdrawal does not shorten it, so a stored email outlived the account. It still travels on the
live envelope — `SendChatService` re-attaches it to the saved message before publish — so only the
**live** privileged view shows it; history and a duplicate-recovery republish never carry it. The cleanup
for documents written before that change is in `infra/AGENTS.md` (Migrations).

## Send path — the order is the contract

`isRoomAlive` → validation → permission → kick → rate limit → profanity → Mongo save → publish
(`SendChatService`). Zero-cost checks first.

- **`concatMap`, never `flatMap`**, on inbound frames — flatMap lets a pipelining client bypass the rate limit.
- **Per-session inbound cap before decoding** (`ChatSessionRegistry.shouldDropInbound`, 10/s burst 20).
  Over-cap frames get no reply; the liveness tap stays **ahead** of the cap.
- **Persist before publish.** A re-insert of the same key goes to `recoverDuplicate`, which republishes the
  stored message when the body matches; the front end drops repeats by `id`.
- **Publish failure is absorbed** — saved + ACK'd is success.
- Rate-limit exemption is role **+ room ownership**.
- `clientMsgId` is **required** (the dedup index is partial on `$type: "string"`); its length cap is in
  `ChatConstants` for both the rejecting and the echoing side.

**Retry contract (SPR-188).** The WS `ERROR` frame carries only a code and `clientMsgId`, and both persist-timeout
branches arrive as `ERROR(INTERNAL)`. On `INTERNAL` and `UNAVAILABLE` the client resends with the **same
`clientMsgId` and the same body** (`chat-frontend-guide.v1.2.md`): not stored → stored now; stored →
`DuplicateKey` → republished and ACK'd; a different body under a used key → `ERROR(CLIENT_MSG_ID_REUSED)`.
A new UUID is the unsafe retry.

## Sessions & shutdown (`ChatSessionRegistry`, streaming-app)

- The outbound buffer is **256 and bounded**. Overflow → close 1013; emit contention → drop that message and
  keep the session. Never merge the two.
- **Close codes:** 1000 room end / lifetime expiry / liveness failure · 1008 entry denial, kick, ban, withdrawal ·
  1009 inbound frame over 4 KB (Netty) · 1013 buffer overflow.
- **For 1008 the SYSTEM code decides retry:** `KICKED` / `BANNED` / `ROOM_ENDED` — do not reconnect;
  `TOO_MANY_SESSIONS` — may retry after backoff; `WITHDRAWING` — end the session. Do not split 1008.
- **1000 is told apart only by a preceding `SYSTEM(ROOM_ENDED)`** — without it, reconnect
  (`chat-frontend-guide.v1.2.md` close-code table). 1009 has no defined front-end behaviour.
- Terminate signals both the sink (`complete`) and a control sink; the first reason wins.
- Fan-out runs on the pod's shared Redis subscriber thread; budgets are per operation, not per session.
- An unflushed close is reclaimed by `NettyConnectionRegistry` after 5s; server idle-timeout does not apply
  after the upgrade.

## Room end — the signal is lossy, so it is checked three times

`live:room:ended` keeps nothing; a pod whose subscription was down misses it.

1. The send path re-checks ended state on a **30s window** (`EntryGate`) and stops asking once ended. "Ended" =
   the chat marker **or** live's key; absence proves nothing.
2. On discovery it closes **that session only**.
3. The session HASH TTL is the last backstop.

- `LiveRoomEndedHandler` writes the marker **first**, every step is independent, and the log level follows
  what was lost.
- The kick set is **expired, not deleted**. ⚠️ That expiry rides only on receiving `RoomEnded`, so a lost end
  signal leaks the key forever.
- **Recovery: `PUT /api/v1/lives/{roomId}/chat/kicked`** (ADMIN, live-app, `ReconcileKickListService`) — only
  adds to a healthy key, refuses a room that is not live, and reports whether the key was corrupted before `DEL`.
- ⚠️ A kick landing after room-end runs `PERSIST` and strips that 24h expiry; nothing orders the two.

## Kick & ban — every input is server-read

- Evidence is fetched **by the server** from `messageId`; `ChatKickLog.from` is the only room·author check.
- **No permission input comes from the body:** owner and liveness from `live-api`, the kicker's role from the
  principal, the target's role from the evidence message's `senderRole`.
- **Order is security:** permission before evidence lookup, liveness after permission; unknown room or user →
  permission-denied.
- Idempotency is `UNIQUE(user_id, live_room_id)` + `ON CONFLICT DO NOTHING`; the affected-row count triggers
  escalation and nothing else — never an early return.
- **DB-first:** commit, then Redis, then publish. Accepted: a crash after the commit leaves an unenforced kick;
  the recovery is kicking again (every step is idempotent).
- **`ChatKickRecorder` exists only to hold the transaction** around the two `@Modifying` native INSERTs — a
  separate bean, `REQUIRES_NEW`. Unit tests cannot see this — mocks never run the query and `@DataJpaTest`
  opens a transaction for you. `ChatKickRecorderTest` calls with `Propagation.NOT_SUPPORTED` to remove that help.
- **Escalation counts distinct kickers** over 2 years, lower-bounded by the user's last release
  (`countConfirmingKickersSince`, `ChatKickLogJpaRepository`). People, not kicks — one seller cannot ban alone.
  Accepted: one seller kicking across their own rooms counts once, only each room's first kicker counts, and an
  ADMIN kick counts as one. ⚠️ `V1__init_live.sql`'s comment describes the old unit and is checksum-locked.
- **Automatic escalation stops at one year.** Permanent bans are human-only and must not go through
  `extendOrCreate` (it overwrites the issuer); a manual-ban path needs its own write.
- **One row per user** (`uk_chat_ban_user_id`). Record and mirror are both **extend-only**; the mirror is what
  enforces.
- **Release `DELETE /api/v1/chat/bans/{userId}`** (ADMIN, live-app): record row → amnesty baseline → mirror,
  **record before mirror**, and the mirror delete is never gated on the row. Kick, release and reconcile carry a
  change version (`chat_ban_change_seq`); the mirror rejects stale ones (`MirrorApply.STALE`).
- **Mirror reconcile `PUT /api/v1/chat/bans/mirror`** (ADMIN, `ReconcileBanMirrorService`) reports missing keys
  (`CREATED`); `EXTENDED` carries no signal. It cannot fix a key with no row.
- **A ban reaches silent sessions only if the pod was listening** (`ChatAccountEventHandler`): reason first,
  then close, and a failed reason must not stop the close. A missed publish has no backstop beyond the send-time
  re-check and the session lifetime.
- **Withdrawal** listens on user's `user:account:withdrawing` (`RedisAccountWithdrawingSource` →
  `AccountWithdrawingHandler`, separate from the ban handler so one source cannot stall the other), writes
  `chat:withdrawing:` first, then closes. A read-only session on a pod that missed it is bounded by
  `chat.session.max-lifetime`.
- **A privileged view in someone else's room is logged at entry** in `ChatSessionRegistry.register`
  (`role`, `sessionId`, `userId`, `roomId`). ⚠️ It is a trail, not an audit record: it says *that* an admin entered, never *what
  they saw* — a real audit needs a store **and** a way for a person to query it.

## Viewer count and roster

- `count` is `HVALS` + distinct — people, not tabs; `HLEN` is a different number (pinned by the repository
  test). The cache + in-flight merge in `ChatSessionRegistry` is the fix for its cost. The residual tail is
  head-of-line blocking on the one shared Lettuce connection, not Redis: the same load through a separate
  connection factory dropped p99 from 113ms to 12ms (measured), so a dedicated connection for this read is the
  cheap answer if it ever matters.
- **The heartbeat re-registers (`HSET`), never touches a TTL** — a touch cannot restore a lost entry.
- **Staleness = 4 × the heartbeat interval**; a static guard refuses below 3×. Counting never writes; a value
  with no separator counts as live (rolling-deploy shape). `HEXPIRE` is unused until Redis 7.4+ is guaranteed.
- One heartbeat timer per pod, interval from `ChatSessionRepository.heartbeatInterval()`. A session's failure is
  absorbed and counted (`chat.sessions.heartbeat_failures`; its WARN latch is shared with two other failure
  modes). ⚠️ **0 does not mean healthy** — a round that never finishes shows only as
  `chat.sessions.heartbeat.skipped` climbing.
- **`ROOM_INFO` is re-sent on change**, riding the heartbeat after re-registration: no last-sent value → send;
  an unknown count → never pushed; the read bypasses the 3s cache; `isRoomOwner` per session (`sendToRoomGated`).

## Scale axis

- **Abuse cap — enforced:** `chat.session.max-per-room` per `(userId, roomId)`; below 1 fails boot; no upper
  bound (`ChatSessionRegistry.maxSessionsPerUserPerRoom`). Over it: `TOO_MANY_SESSIONS` + 1008, counted by
  `chat.sessions.admission_capped`; the configured value is exposed as `chat.sessions.max_per_room`.
  - Not covered: guests (a fresh `userId` per entry) and cross-pod (bound = cap × replicas).
  - Only **dead** slots are reclaimed (`reapUnresponsiveIfFull`, handed `deadline + interval`, counted as
    `chat.sessions.reaped`); it frees the slot and does not terminate. The ~2 min window after a silent drop
    cannot be closed without cutting live sessions; the root fix is `jti` on the room token, in live.
  - **Live sessions per `(user, room)` can reach 2× the cap for ≤ 8s** (`FORCED_CLOSE_GRACE 3s +
    CLOSE_FLUSH_TIMEOUT 5s`) and do not stack while `liveness-deadline` ≫ 8s. `SessionTimeouts` does not stop a
    deadline below that.
- **Capacity cap — not implemented.** Registry heap is ~2 KB per idle session (measurement and its Mockito
  trap: `ChatSessionRegistry.localSessionCount`), so the number has to come from a real pod's other axes (the
  load track), not a guess.
- Serialization is shared per fan-out (`OutboundFrame`). The four per-recipient variants stay four: the sender
  axis carries `clientMsgId` (front-end contract). Batching is unmeasured.

## History (VOD)

`GetChatHistoryService` (chat-core, **blocking**) ← `ChatHistoryController` (live-app).

- Three mutually exclusive modes: past cursor (`beforeId`), reconnect cursor (`afterId`, SPR-201), VOD window
  (`playbackPositionMs`). `findAfter` is the only one that returns **oldest first** (`ChatHistoryRepository.findAfter`).
- `findBefore` · `findAfter` read exactly `size` (the front end infers `hasNext` from a full page) — not `CursorPage`.
- **Kicked senders are excluded inside the Mongo query** (`$nin`); filtering after makes the scroll stop silently.
- The privileged view is `seesUnmaskedContent`; an unknown requester role throws; anonymous is allowed, but
  `requesterId` and `requesterRole` come as a pair.
- Window: 5 min from `LiveRoomView.startedAt`, capped at 200; a never-started room is rejected.
- Command violations → `CHAT-009` (400); the global handler was deliberately not widened.

## Logging

- Structured ECS JSON by default (`StructuredLoggingDefaults`, `common/global`).
- **Correlation goes to the Reactor Context, not MDC** (`Hooks.enableAutomaticContextPropagation()`); field names
  live only in `LogFields`, snake_case. The Redis subscriber thread has no session scope, so its pipelines write
  `room_id` / `user_id` themselves.
- **ERROR means a human must act.** Transient degradation is WARN (the meter carries the rate); `WRONGTYPE`, a
  payload parse failure, a failed room-end / account-event step and a failed forced-close signal are ERROR.
  Pinned by a test.
- Runtime log-level changes are unavailable (no Spring Security to protect `loggers`); use
  `LOGGING_LEVEL_<PACKAGE>` and a restart.

## Observability

`ChatMetrics` (`application/port`) ← `infrastructure/metrics`; `ChatMetrics.NOOP` is picked through
`ObjectProvider`, not `@ConditionalOnBean`. Names live in `ChatMeterNames`.

| Meter | Tags | What it means |
|---|---|---|
| `chat.policy.open` | `policy`, `checkpoint`, `failure` | a moderation read failed and the request was let through |
| `chat.send.refused` | `policy` | a send-side moderation read failed and the send was refused before persist — per tag, so alert on `max by (policy)` |
| `chat.ratelimit.degraded` | `mode` | this pod judged the rate limit locally |
| `chat.delivery.dropped` | `stage` | persisted, live delivery lost |
| `chat.session.cut` | `reason` | an established session was cut by a server decision |
| `chat.dictionary.fastpath.lost` | — | the fast-path dictionary swap was missed; this pod enforces with a stale one |
| `chat.persist.timeout` | `stage` | the Mongo operation bound cut a persist step |
| `chat.subscription.resubscribed` | `subscription` | a pub/sub subscription retried its establish — read the rate |
| `chat.withdrawal.record.failed` | — | `chat:withdrawing:` could not be written; the close still ran |
| `chat.inbound.dropped` | — | a frame over the per-session inbound cap was dropped; expected 0 |
| `chat.fanout.duration` | — | Timer: one chat message fanned out to this pod's sessions in the room (KICK not timed) |
| `chat.sessions.heartbeat.duration` | — | Timer: one completed heartbeat round; a stuck round is never recorded |
| `chat.sessions.heartbeat.skipped` | — | a heartbeat tick dropped because the previous round was still running |

streaming-app's `ChatSessionGaugeConfig` registers the session meters (`chat.sessions.local`,
`chat.rooms.local`, `chat.sessions.admission_capped`, `.reaped`, `.max_per_room`, `.heartbeat_failures`); the
on-call reading of all of them is in `infra/AGENTS.md`.

- **The adapter never throws** — `safe()` plus null-tolerant tags, pinned by a throwing-registry test.
- **Never sum `chat.persist.timeout` with `chat.delivery.dropped`, nor its two stages** (`PersistStage`):
  `write` = the result is unknown (a client bound does not cancel the server write), `duplicate_recovery_read`
  = the document already exists.
- `failure=store_corrupted` is never merged into `unavailable`; `checkpoint` separates entry from send.
- `chat.session.cut` does **not** cover entry denials (`EntryGate`, before a session exists) or 1009.
- Every meter is 0 in the healthy case. **Adding a meter = a line in `ChatMetricsPrometheusScrapeTest` and in
  `MicrometerChatMetricsTest`** — both hand-enumerate, and a hand-enumerated guard is only as complete as the
  last person who remembered it.
- **Exposure is locked in code** (`ObservabilityExposureConfig`): the management port must differ from the
  user port and the actually-exposed endpoint set must be exactly `{health, prometheus}`.

## Startup — the port is the deadline, not the runner

The port binds before `ApplicationRunner`s run (`EagerSingletonsRunBeforeThePortBindsTest`), so **anything a
request depends on finishes in a bean body** — Mongo index creation is there for that reason. Its worst case is
10 min 30 s (`ChatMongoConfig.INDEX_CREATE_CEILING` + `INDEX_DROP_CEILING`, `ChatMongoIndexCeilingTest`); the
`startupProbe` sizing is in `infra/AGENTS.md`.

## Tests

- Boot 4 has **no Mongo slice**, and `MongoProperties` binds to `spring.mongodb` — prefer `@ServiceConnection`.
- `ChatCoreTestApplication` re-adds `TypeExcludeFilter`; without it every slice runs a full scan.
- Any `@SpringBootTest` on chat-core needs a Redis container (`RedisChatBroadcaster` connects in its constructor).
- JPA schema comes from the **real Flyway files** (`support.LiveSchema`), and `db/migration/live` is a declared
  input of `:chat-core:test`.
- **A red test still has to say so.** Every `StepVerifier` terminator, `.block()` and `CountDownLatch.await` in
  `modules/chat` + `apps/streaming-app` tests carries a ceiling (`VERIFY_CEILING`); a ceiling-less one hangs
  instead of failing and voids every mutation verdict in that run.
- **A test that supplies wiring the app does not is worse than no test** — `ChatModerationWiringTest` boots the
  real live-app context.
- **A test claiming a guarantee is mutation-checked.** `checkOrphanJavadoc` gates javadoc placement in `check`.

## Persistence mapping

`@Mapper(unmappedTargetPolicy = ERROR)` for chat; deleting the attribute is caught by ArchUnit rule 20
(`mapstruct_mappers_must_fail_on_unmapped_targets`). The round-trip test replaces neither — a `null` argument
passes it.
