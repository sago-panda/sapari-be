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

## Failure policy — the moderation reads open, the rate limit degrades

Every moderation read (kick, ban, room-ended) is **fail-open**. The rate limit is **not** one of them
any more: it falls back to a pod-local window of the same length, so a Redis **outage — or a round trip
slower than 200ms** — no longer removes the cap. The latency half matters: dropping packets instead of
dying keeps the command pending to Lettuce's 60s default, and without the bound the fallback never fires.
It also means degradation is no longer a rare event you can leave to a throttled `log.error`; it fires on
latency, so "what fraction of the time was enforcement pod-local" wants a metric, not a log line.
There is still no fail-closed path in chat.

| Read | On failure | Loses |
|---|---|---|
| room-ended marker (entry + send re-check) | allow | ended-room blocking |
| kick set | allow | kick enforcement |
| ban key | allow | ban enforcement |
| rate limit | **degrade** (pod-local 3s window) | cross-pod accuracy (≈ N pods × 1 per window) |
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

**Never hand a record to a logger.** `ChatSession`'s default `toString()` prints `email`, so one
`log.info("... {}", chatSession)` leaks PII that every call site is currently careful to avoid — the 12
production log statements all pass `sessionId`/`roomId`/`userId`/`role` scalars. The same holds for any
record that carries `email` or an unmasked body. Absorbing a failure is also not the same as silencing
it: when a handler swallows to keep a verdict, it still owes a counter and an interval-throttled log
(`RedisRateLimiter.denied` is the worked example — it kept the verdict and lost the signal until that was
put back).

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
| `chat:account:events` | Pub/Sub | account-scoped, **deliberately outside `PUBSUB_PREFIX`** — the room pattern parses its suffix as a UUID, so a name under it makes every pod log a parse failure and drop the envelope |

`live:room:ended` is **live's channel** — subscribed, never written, and deliberately not in `ChatRedisKeys`.

Only the kick SET is exposed to WRONGTYPE (`SISMEMBER` type-checks). `SET`/`EXISTS` keys are not, so the
corruption split is not widened to them.

## Wire contracts — four, and all four fail silently when broken

0. **The room token must carry `iat`.** `RoomTokenVerifier` rejects entry when the token is older than
   `max-lifetime`, which bounds the window in which a stale `role`/`owner` can start a *new* session —
   the token has no `jti`, so it is replayable until `exp` and the entry gate re-checks only room-ended,
   kick and ban. live sets it today (`RoomTokenProvider.issue` → `.issuedAt(...)`). **This one does not
   fail silently** — a missing `iat` is rejected like a missing `exp`, so dropping it on the live side
   fails every handshake loudly rather than quietly removing the bound. That is the point: the
   alternative is losing an enforcement nobody would notice.

1. **`ChatEnvelope`** (`application/protocol`, not infrastructure — the port returns it). Both the reactive
   broadcaster and the blocking kick publisher serialize the **real type**; hand-built JSON was the old
   design and a one-character field-name typo compiles fine and vanishes across every pod.
   - The broadcaster disables `FAIL_ON_UNKNOWN_PROPERTIES` for the same reason contract 4 does: a rollout is
     never atomic, so an older pod *will* receive envelopes carrying a field it does not know, and strictness
     makes it drop **every** envelope the newer publisher sends. **Only one direction is bounded by that.**
     Add envelope fields **nullable and unvalidated** — a required one makes newer pods drop every *older*
     envelope instead, and KICK_EVENT shares this channel, so moderation goes with it.
   - Lenience covers *properties*, not *type ids*. Both discriminators (`ChatEnvelope.kind`,
     `ChatMessageTypeMixin.kind`) have no `defaultImpl`, so an unknown `kind` still throws — a **new envelope
     kind reaches older pods as a silent drop of exactly that kind**. Ship the consuming version first, or
     give the discriminator a `defaultImpl` that explicitly ignores unknowns.
   - Lenience also turns *rejections into nulls*: a mistyped field name now yields a null component instead of
     an error. Envelope records therefore validate their own required fields (`KickEvent` rejects a null
     `userId`; `ChatMsg` relies on `ChatMessage`), because this channel is outside the trust boundary — the
     channel-name parser says as much.
2. **`ChatMessageTypeMixin` registration is mandatory** on any mapper touching a CHAT envelope. Omission
   dies loudly on first deserialize — that is the good case.
3. **`OutboundMessage` omits null fields** (`@JsonInclude(NON_NULL)`). It is an 8-type union, so over half
   is empty in any frame; the padding used to exceed the message body. Front-end contract: absent key, not
   `null` — `=== null` breaks, everything else does not.
4. **`ChatAccountEvent`** (`application/protocol`) on `chat:account:events` — chat's own representation,
   **not a cross-domain contract**: it lives in chat-core, so no other module can publish with it. The
   receiver disables `FAIL_ON_UNKNOWN_PROPERTIES`; adding a field otherwise makes every older pod drop the
   event mid-rollout and ban enforcement silently reverts fleet-wide.

**PII gating happens at fan-out, not on the wire.** The envelope carries `senderEmail` and the unmasked
original in plaintext because a privileged reader may be on a different pod. The masked view is the default
and the privileged one is the exception — **never invert that**, or every new role leaks by omission.

Privileged means **room owner or ADMIN** — a decision, not a default: moderators hold the same chat rights a
seller does. What it accepts is that the email is never read by the moderation path and that an ADMIN, unlike
an owner, is scoped to no room at all; the entry log (once per connection) is what answers "who looked, and
when" in exchange. Splitting the two — original text without the email — is one enum constant away, which is
why the axes are separate types rather than a boolean pair.

Ownership and role are two different axes on purpose. Ownership is
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
- Close codes: **1000** room ended, session-lifetime expiry, and liveness-check failure (all three on purpose
  — see below), **1008** entry denial / kick / account-level ban, **1009** inbound frame over the 4 KB limit
  (Netty's decoder closes; the reason string leaks the configured limit), **1013** buffer overflow.
  1013 rather than 1000 exists to stop instant reconnect.
- ⚠️ **1000 now means three different things, and only one of them carries an explanation.** Room end is
  preceded by `SYSTEM(ROOM_ENDED)`; lifetime expiry and liveness failure send no frame at all, because the
  only thing the client should do is reconnect. **That makes "did a SYSTEM(ROOM_ENDED) arrive just before the
  close?" the sole signal separating "the broadcast is over" from "reconnect now"** — an implicit contract the
  front end must hold. If it instead reads 1000 as "ended", every healthy viewer sees "방송이 종료되었습니다"
  once per lifetime window. **The front-end behaviour is not verifiable from this repo** (no front-end code
  here), so treat it as an assumption to confirm, not a measured fact. Giving expiry its own code would remove
  the ambiguity at the cost of a new front-end contract.
- ⚠️ **1009 has no defined front-end behaviour yet** — nothing in this repo mentions it besides the limit
  itself. A client hitting it is sending frames the protocol layer refuses, so retrying unchanged will loop.
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

The propagation is `REQUIRES_NEW`, and that word is load-bearing: under `REQUIRED` the "returned means
committed" invariant silently degrades to "no caller above happened to open a transaction", and the moment
one does, Redis and publish run *before* the commit. **A test that supplies its own boundary cannot see
that** — the recorder just joins it and everything still looks right. The one that can opens an outer
transaction, calls inside it, rolls the outer back, and asserts the log survived.

The mirror write is **extend-only** (`PTTL` compare + `SET` in one `EVAL`). Concurrent kicks in different
rooms each create their own ban and each write the mirror, so an unconditional `SET` lets whoever arrives
last win — and a short TTL landing last releases a user weeks early, because **the mirror is what enforces**.
Re-reading the record for the longest ban was tried and only narrows it: it fixes *what* gets written, never
*who writes last*. Ordering problems need order-independent writes. The cost is that this port can no longer
shorten a ban — an admin reprieve needs `DEL` + set, in the same change that adds it.

Escalation counts **distinct kickers** across rooms on a 2-year window — people, not kicks. Counting rows
would let one seller reach the threshold alone: the log is unique per `(user, room)`, so three broadcasts and
one kick in each makes three. Three broadcasts is an ordinary week, not a conspiracy, and that would hand
every seller a platform-wide ban over anyone who chats in their rooms. Counting people makes the threshold
mean **three independent judgements**. It costs nothing against the case the window exists for: a user who
rotates rooms is kicked by different people anyway. **The opposite direction is open and has no backstop**:
one seller kicking the same person across ten of their own broadcasts still counts as one, and nothing sits
above that — there is no per-seller ban and no manual ban path. That is the price of this choice, not an
oversight. Note also that the count is really *"three rooms whose **first** kicker differs"* — `ON CONFLICT
DO NOTHING` keeps only the first kick per room, so a second, independent judgement in the same room never
counts. Under-enforcing, and accepted. Scoping per-seller is still wrong for the same reason as
before — it lets a rotating user reach no threshold at all. Thresholds are read as **at-or-above**, not exact — the design doc's table
gives the same answer while kicks arrive one at a time, and differs only where the table is silent (window
shrink, a concurrent kick skipping a threshold). With people as the unit, **only the first rung is realistically reachable** — six or nine distinct
moderators inside two years is rare, so the ladder effectively ends at one week. The numbers were kept
deliberately; the ladder being sparse is the intended shape while no release path exists. **An `ADMIN` kick
counts as one like any other.** That is a decision, not an omission: "one admin ⇒ instant ban" would be a
manual ban wearing `banned_by_id = SYSTEM`, which erases who decided. Admin judgement belongs in a manual
ban path, not in automatic escalation. ⚠️ `V1__init_live.sql`'s comment still describes the old unit
(`3회→1주`, counting kicks) and is **checksum-locked** — this file is the current one.
**Automatic escalation stops at one year.** The doc's
12-kick permanent ban was moved to a human's hands: nothing reversible-only-by-hand should be applied by a
server that has no code to reverse it, and that matches every other call this domain has made (the kick set
expires rather than being deleted; a corrupted key is not self-healed). Permanent bans still exist as rows
with a null expiry — an admin puts them there — but **not through `extendOrCreate`**. That path overwrites
`banned_by_id` and `created_at` whenever a longer expiry arrives, so a later automatic escalation would
replace the admin's identity with `SYSTEM` and the admin's action would exist nowhere (`chat_kick_log`
records kicks, not manual bans). Only a permanent manual ban is safe today, protected by the
`existing.expires_at IS NOT NULL` guard; a dated one is not. A manual-ban path needs its own write —
and the decision about preserving the previous issuer (a history row, or leaving those two columns
alone on conflict) belongs to **that** change, before any manual ban exists to lose. A plain `INSERT`
for a user who already has a row is rejected with `23505`, so it cannot be that write either. A duplicate
kick does **not** re-count: it would let a seller extend a ban indefinitely by re-kicking.

**One row per user is a DB constraint** (`uk_chat_ban_user_id`), not a convention. Concurrent kicks from
different rooms used to write two rows — READ COMMITTED hides each other's uncommitted row, so a transaction
does not stop it; a constraint does. Two rows made un-ban half-work: deleting one leaves the other
enforcing, and the screen says "released". **Both stores are now extend-only** — the record's write is
`ON CONFLICT DO UPDATE` guarded by a longer expiry, matching the mirror's Lua. They are not the *same* rule
(the record compares absolute instants, the mirror compares remaining TTL) but they converge on the same
effective state, and where they differ the mirror is never shorter — the safe direction.

**A ban reaches sessions that never speak — but only if the pod was listening.** The entry gate stops new
connections and `SendChatService` re-checks on every frame, so a banned user is blocked the moment they
*talk*. A session that sits silent in another room used to survive indefinitely; `chat:account:events` now
closes it. What remains open is narrow and worth knowing: Pub/Sub is not durable, so a pod whose
subscription was down at publish time never sees the event, and a silent session on that pod stays until it
speaks, the room ends, or the next kick republishes. **Widening `EntryGate`'s 30-second recheck window does
not help** — that window only runs on the send path, and anyone sending is already caught. Closing it needs a
periodic per-pod sweep, priced against session count. The order there is a contract: **reason first, then
close** (a closed sink drops the frame, leaving a 1008 with no explanation), and **a failed reason must not
stop the close** — a notification is worth less than a banned user staying connected.

**An ADMIN entering someone else's room is logged, and that is all it is.** *(The design doc specifies no
admin-entry audit; chat chose this shape. If §6 ever regulates one, compare against the choices below rather
than assuming this predates it.)* The line lives in
`ChatSessionRegistry.register` — where the *session* is admitted, not where the token is issued (a token that
never connects saw nothing) — and carries `sessionId`, `adminId`, `roomId` so it joins this file's close and
drop lines. Deliberately nothing else: an audit records the access, **not a copy of what was read**, and the
room's owner is not on the token (`owner` is a boolean) so naming them would cost a live lookup on every
entry, for a value `roomId` already answers after the fact. **Fan-out and audit ask the same question through one function.**
`ChatPermissionPolicy.seesUnmaskedContent(role, isRoomOwner)` decides who reads the original, and
`usesPrivilegedViewInSomeoneElsesRoom(...)` is that plus "not their own room". They used to be written out
by hand in two modules, which meant a new role granted the privileged view could reach fan-out without
reaching the audit — **more exposure, less trace**, and silently. The two cannot drift because there is one function; what a
parameterised test adds is a **table written independently of the policy** — deriving the expectation from
the policy makes the assertion a tautology that survives opening the original to everyone (it did, once).
Widening the privileged view is caught by `PrivilegedEntryAuditTest.ordinaryEntriesAreNotRecorded`, which is
the actual tripwire. The policy
returns a boolean rather than `ChatMessageVisibility`: that enum lives in `application`, and domain may not
depend on it (ArchUnit enforces this). `ChatSession` also rejects `ADMIN` with `isRoomOwner`, so the
own-room half is unreachable today — a test pins that invariant so it gets looked at with the audit
condition if live and chat ever settle that combination.

⚠️ **This is a trail, not an audit record.** Retention, search and integrity all live in log shipping, which
this repo does not configure. And it answers *that* an admin entered, never *what they saw* — a room with no
messages and one that streamed ten thousand emails to them produce the identical line. Per-message logging
was rejected on fan-out cost, so that gap is a choice, not an oversight: a real audit needs a store **and** a
way for a person to query it. `chat_kick_log` shows the difference — it is read by escalation, but nothing
lets a human ask it anything, which is what "a store is not an audit" looks like.

**The envelope is chat's own, not a cross-domain contract.** `ChatAccountEvent` lives in chat-core, so no
other module can publish with it (`X-core → Y-api ONLY`; `-core` is never depended on). When withdrawal
(T-11) needs the same effect, follow `live:room:ended`: the publisher owns the wire shape and chat adapts it
in an adapter. That costs one more channel and subscription and buys keeping the type inside this module.

The record can only be shortened by deleting the row, and **the mirror must be deleted in the same change**:
enforcement reads `chat:banned:`, never the table. Order it record → mirror — a break in the middle leaves
over-blocking, while mirror-first can *resurrect* the ban (a surviving row is picked up by the next kick and
re-written to the mirror). And **never gate the mirror delete on the row still existing**: after a
record-first break the row is already gone, so a retry that stops at "no ban to release" never reaches the
mirror. The whole release command must be safe to run again — deleting an absent key is a no-op. Over-blocking
only heals by itself for dated bans; a permanent one is mirrored without a TTL and stays until a human acts.

That used to cost a hole — a failed ban write after a committed log meant the retry took the duplicate path
and skipped escalation. **The transaction closed it**: a failing ban INSERT rolls the log back with it, so
the retry starts fresh — pinned by a test that swaps in a throwing ban store and asserts the kick count is
unchanged. What can still fail outside the boundary is the Redis mirror, and a retry heals that
because the active-ban lookup runs *before* the duplicate check and hands the caller the ban to re-mirror.
That ordering is the whole self-healing story, so it is pinned by a test.

## Viewer count — `HVALS` is not the bottleneck it looks like

`count` is `HVALS` + distinct, and the O(N) is deliberate: the HASH is one row **per tab**
(`sessionId → userId`) and the number shown is **people, not windows**. `HLEN` is therefore not an
optimization but a different number — measured off by the multi-tab factor (1 user, 2 tabs → 2×). The
repository test pins this, so the swap fails the build.

The cost was investigated and **no redesign is warranted** (measured, redis:7 container, 36-byte UUID values):

| Shape | Worst concurrent command |
|---|---|
| `HVALS`+distinct alone, 12,000 entries | p50 3ms / max 6ms |
| 100 concurrent on the *same* 12,000-entry hash | SETNX **211ms** — crosses the rate limiter's 200ms |
| after in-flight merge: 60,000 sessions in one room | 28ms |
| after merge: 6 pods × 100 rooms (360,000 entries at once) | 130ms |

So the cache + in-flight merge in `ChatSessionRegistry` is not partial mitigation — it *is* the fix, and it
holds at five times the sizing model. Entry-gate reads (`EXISTS`/`SISMEMBER`) stayed at 0–2ms throughout.

And the residual tail is **not Redis being single-threaded**: one `ReactiveStringRedisTemplate` means Lettuce
shares a single native connection, so a large `HVALS` head-of-line-blocks the commands behind it. The same
load through a separate connection factory dropped p99 from 113ms to 12ms. If this tail ever matters, a
dedicated connection for this one read is the cheap answer — a maintained counter is the expensive answer to
the wrong cause.

## Logging — the level is the required response, and the field is the query

Structured ECS JSON for every app, set as *default* properties from `common/global`
(`StructuredLoggingDefaults`). Two measured traps live there: Boot 4 moved `EnvironmentPostProcessor` to
`org.springframework.boot` and left a `@Deprecated` twin in `...boot.env`, so the old one compiles and
silently never runs; and registration is `META-INF/spring.factories`, not `.imports`. A test asserts the
actual console bytes, because the property can read `ecs` while the output is plain text.

**Correlation is written to the Reactor Context, not MDC.** MDC is a `ThreadLocal`, and measured it is
`null` after a `publishOn` and `null` inside `onErrorResume` — where nearly every degradation log in this
module lives. `io.micrometer:context-propagation` plus `Hooks.enableAutomaticContextPropagation()` bridges
it; `ChatWebSocketHandler` writes `session_id`/`room_id` for the handshake and `user_id` inside the verified
session. Field names are snake_case and live only in `LogFields` — ECS lifts MDC straight to top-level JSON
beside `log.level` and `error.stack_trace`, so camelCase would put two conventions in one object.

- **No `request_id` on the reactive side.** A WS session, not a request, is the lifetime unit, and
  `session.getId()` exists from the first line. A second identifier for the same thing just raises the
  question of which one to group by.
- **The Redis subscriber thread has no session scope**, so fan-out, room-end and account-event pipelines
  write `room_id`/`user_id` themselves. Those three run only in streaming-app (live-app publishes and
  excludes `com.sapari.chat` from scanning), so the hook is always on where they run.
- `addKeyValue` (single-line fields) also reaches the JSON, verified — it is the only option where no scope
  exists. Whether a key present in *both* MDC and `addKeyValue` stays valid JSON was **not** measured, so
  nothing in this module creates that case.

**Level discipline: ERROR means a human must act.** Now that the degradation metrics exist, "log it at ERROR
or it disappears" is no longer a reason — the meter carries the rate, and alerting belongs there, not on
individual lines.

| Shape | Level | Why |
|---|---|---|
| Moderation read failed, request let through | WARN | heals when Redis returns; `chat.policy.open` has the rate |
| Rate limit degraded / TTL fallback | WARN | enforcement continues, only accuracy is lost |
| Publish or fan-out dropped | WARN | the message is persisted; only live delivery was lost |
| **Key type conflict** (`WRONGTYPE`) | **ERROR** | retries never heal it; a human must clear the key |
| Payload parse / deserialize failure | **ERROR** | a contract mismatch; it takes a deploy |
| Room-end or account-event step failed | **ERROR** | control is lost — the room stays open, the ban is not applied |
| Forced-close signal failed to emit | **ERROR** | that session can no longer be cut |

The contrast is what matters, so it is pinned by a test: raising the transient branch to ERROR is a
one-character change that breaks nothing otherwise, and it would bury the lines that need action.

**Runtime log level changes are deliberately unavailable.** The actuator `loggers` endpoint would do it, but
it mutates state over POST and this app has no Spring Security — exposing it on the unauthenticated
management port would let anyone reachable there turn on DEBUG and flood the log pipeline. Until there is
auth or a NetworkPolicy, levels change by `LOGGING_LEVEL_<PACKAGE>` and a restart. That is also why this
module has **no DEBUG logging worth enabling** (measured: 0 of 143 statements repo-wide are DEBUG) — adding
it only pays off once it can be switched on.

## Observability — what is measured is the policy *not* being enforced

`ChatMetrics` (`application/port`, no micrometer) ← `infrastructure/metrics`. ArchUnit rule 16 already
covers chat by wildcard — verified by planting a `MeterRegistry` field in an application class and watching
it fail. `ChatMetrics.NOOP` is selected via `ObjectProvider` when no registry exists, **not**
`@ConditionalOnBean`: that condition is evaluated before autoconfiguration, so it picks NOOP even when a
registry does arrive. Measured — the wiring test supplies the registry *from an autoconfiguration*, and the
conditional variant really does fall back to NOOP.

| Meter | Tags | What it means |
|---|---|---|
| `chat.policy.open` | `policy`, `checkpoint`, `failure` | a moderation read failed and the request was let through |
| `chat.ratelimit.degraded` | `mode` | pod-local window, or a denial whose TTL read failed |
| `chat.delivery.dropped` | `stage` | publish failed / session emit contention / ping tick dropped |
| `chat.session.cut` | `reason` | the server closed a session |

- **The adapter must never throw.** Most call sites are inside `onErrorResume` lambdas — a throw there
  propagates downstream and *denies* the request the policy decided to allow. Instrumentation would invert
  the availability policy. `safe()` plus null-tolerant tags, pinned by a throwing-registry test.
- **`failure=store_corrupted` is never merged into `unavailable`.** A corrupted key does not heal on retry
  or on Redis coming back; a human must clear it. Merged, the permanent condition hides under the transient one.
- **`checkpoint` separates entry from send.** Open at entry only means a sanctioned user is watching; open at
  the send re-check means posts land in an ended room. A mutation test caught that this distinction was
  initially unguarded.
- **`chat.session.cut` exists because close 1000 means three things** (room end, lifetime, liveness) and only
  room end is preceded by an explaining frame. `MODERATION` (1008) and `BUFFER_OVERFLOW` (1013) are in the
  same meter so the reasons sum to all server-initiated closes.
- Every meter is **0 in the healthy case**, which is what makes alerting possible — tests assert the counters
  are not even created on a healthy path.
- The Prometheus registry rejects same-name-different-tag-key meters, so a call that omits a tag makes that
  series vanish silently at runtime. A scrape test walks every enum value; add a line when adding a meter.
- **Exposure is locked in code, because this app has no Spring Security.** `ObservabilityExposureConfig`
  refuses to boot unless `management.server.port` is set and differs from `server.port`, and unless
  `exposure.include` is a subset of `{health, prometheus}`. It reads that key with `Binder`, not the base
  property: written as a YAML list the key lands as `include[0]`, and a base-key read lets a list-form `"*"`
  through (measured). `"*"` would serve `/actuator/env` and `/heapdump` unauthenticated — Mongo credentials
  and the Redis password.
- Port reachability is pinned both ways by a test that boots the real app with containers and makes real HTTP
  calls: 404 on the user port, 200 on the management port. **Expect 404, not live-app's 401** — there is no
  security chain here to answer first. Unlike live's equivalent it runs in CI, because the infrastructure this
  app needs is two containers and one throwaway public key.

## Startup — the port is the deadline, not the runner

Measured boot order (`EagerSingletonsRunBeforeThePortBindsTest`, fixed port, real TCP connect):

| Stage | Port |
|---|---|
| singleton pre-instantiation (`@Bean` method bodies) | refused |
| earliest `SmartLifecycle` (`phase = MIN_VALUE`) | refused |
| `ApplicationRunner` | **accepted** |

The reactive web server is a `SmartLifecycle` at phase 2,147,481,599, so it binds inside `finishRefresh()`;
runners come after. **Anything a request depends on must therefore be finished in a bean body, not a runner** —
Mongo index creation is there for exactly that reason. Note the web server *object* exists during
`onRefresh()`, long before `start()` binds, so its presence proves nothing (measured).

Why it matters here and not everywhere: streaming-app has **no actuator**, so the only readiness probe
Kubernetes can attach is a TCP socket check. A runner blocked on Mongo answers that probe `Ready` for its whole
duration — three indexes × 30s server selection = up to 90s of handshakes routed into a pod that is about to
die. Failing inside refresh is not a new failure mode: a throwing runner already broke
`SpringApplication.run()` (measured). It only moves the failure to before the port opens.

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
- Schema for JPA tests is applied from the **real Flyway files** — all of them, in version order
  (`support.LiveSchema`). A second copy drifts while staying green, and reading only `V1` leaves every
  later migration untested while the suite stays green.
- **`db/migration/live` is declared an input of `:chat-core:test`.** It is not one by default — the SQL is
  read at runtime through `user.dir`, so changing only a migration used to leave `test` UP-TO-DATE and a
  schema mutation appeared to break nothing (measured: 832ms green with the unique index deleted). The
  `inputs.dir` line in `build.gradle` closes it; if you move where the schema comes from, move that line too.
- **A red test still has to say so.** Every `StepVerifier` terminator without a ceiling —
  `verifyComplete()`, bare `verify()`, `verifyError(...)`, `verifyErrorMessage(...)` — waits forever for a
  signal that a regression may have removed, so the test *hangs* instead of failing (measured: 110s and still
  running on a missing close, 2m34s on a `thenCancel().verify()` whose `expectNext` never arrived).
  **Every call site now carries one** — 117 of them across 15 files were converted to
  `expectXxx().verify(VERIFY_CEILING)`, with `VERIFY_CEILING = Duration.ofSeconds(10)` declared per test
  class. Verified end to end: making a signal never arrive now fails in 12s with
  `VerifySubscriber timed out` instead of hanging. Keep it that way — a ceiling-less terminator does not
  just hide one bug, it voids **every mutation verdict in that run**, because the suite cannot report at
  all. "passing ≠ catching" has a twin in "failing ≠ telling".
  - **`.block()` carries the same rule** — every test call site carries a ceiling; verified end to end by
    making `register` return `Mono.never()`, which turns the class red at the ceiling instead of hanging.
    **Count a conversion against the commit it lands on and state the scope** — fixing only the baseline
    lets the scope drift instead. This sweep is 201 sites measured at its own parent over
    `modules/chat` + `apps/streaming-app` tests. Three commits earlier the same scope gives 199 (two
    tests added in between call `register(...).block()`); including `src/main` gives 204; widening to the
    whole repo adds two *comment* lines in `architecture-test` that merely mention `.block()`. Two of
    those four numbers landed on 201 by different routes — **the counts matching is not evidence that the
    same thing was counted.**
  - **Production has 3 of its own, and they are a different kind of bounded** — do not read them as part
    of the rule above. `ChatMongoConfig`'s startup index creation blocks, and against a closed address it
    throws `DataAccessResourceFailureException` after **30,053 ms** (measured) — the Mongo
    `serverSelectionTimeout` default. That is finite, but what it buys is "boot fails loudly" and the
    cost is a **30s stall per index, up to ~90s for the three**, which a readiness probe can turn into a
    restart loop. The test rule buys "the suite goes red and reports"; these buy "the pod dies instead of
    serving". A `VERIFY_CEILING`-style value is not the lever here — the probe configuration is
    (`infra/`), so this is recorded, not changed.
  - **`await()` is the third strand** — a start latch or barrier with no ceiling leaves the worker blocked
    forever when the main thread throws before `countDown()`. The reason is **not** that a non-daemon
    thread holds the worker JVM open: it does not (a thread sleeping 10 minutes still lets the Gradle test
    worker exit cleanly, measured). The reason is that the stuck thread keeps hammering the sinks and
    registry and so pollutes the *next* test in the same JVM. All 7 sites take `await(30, SECONDS)` and
    throw with a reason.
  - **The uniform 10s costs nothing on a passing run** — a ceiling is only spent when it is exceeded, and
    the worst case is **not** ceiling × call sites. An exceeded `block(Duration)` *throws*
    (`IllegalStateException: Timeout on blocking read…`), which aborts that test method, so the remaining
    calls in it never run. The bound is ceiling × (methods and worker threads that reach a stall), not
    ceiling × call sites — in `ChatSessionRegistryTest` that is **~100 call sites spread over ~60 test
    methods** (measured at `eecde755`; the counts move with every commit that adds a test, which is why
    the rule two bullets up says to state the baseline), so the order of magnitude is minutes, not tens
    of minutes. Slow, but it *reports* — that is the trade.
  - **Scope of "every hang is bounded"**: that claim covers `modules/chat` + `apps/streaming-app` only.
    `modules/live` still has one (`ActiveRoomGaugeTest` — a bare `await()` plus an unbounded
    `Future.get()`), and live is outside this ticket's reach. State the scope whenever the claim is
    repeated; a count or a claim taken over a narrower scope does not verify a repo-wide sentence.
  - **Don't size a ceiling off a test case's reported time.** The ceiling bounds the *terminator's wait*,
    not the case — setup, `Thread.sleep` before `StepVerifier.create`, and an inner `.timeout(...)` all sit
    outside it. Picking values off per-case durations once produced a "raise the ceiling" commit whose slow
    tests turned out to use an inline literal rather than the constant, so the margin did not move at all.
  - **A slow case is not automatically a load-sensitive one.** `RedisChatBroadcasterTest` and
    `RedisLiveRoomEndedSourceTest` take ~1.2–1.5s per recovery case, but they are mock-based (no
    `@Testcontainers`) and that time is the production `Retry.backoff(…, ofSeconds(1))` constant plus its
    jitter — a scheduled delay does not stretch under CPU pressure the way a `park`-vs-busy-spin margin
    does. The actually container-backed classes among the converted 15 are `CrossPodBroadcastTest`,
    `ChatKickRedisRepositoryTest` and `ChatMessageRepositoryImplTest`.
- **A test that supplies wiring the app does not is worse than no test** — it goes green while production
  breaks. Both of this branch's runtime failures hid behind exactly that (`@DataJpaTest`'s transaction, a
  test-local UUID customizer). `ChatModerationWiringTest` boots the real live-app context and asserts on the
  beans it actually built; it supplies its own properties so CI runs it rather than skipping it by tag.
- **A test claiming a guarantee must be mutation-checked**: revert the production line, confirm that test —
  and ideally only that test — fails, restore. `checkOrphanJavadoc` gates javadoc placement in `check`.

## Persistence mapping — the policy is the net

`@Mapper` here is `unmappedTargetPolicy = ERROR`, not the MapStruct default (`WARN`). Under the default, a
field added to a domain record but not to the mapper leaves that target empty and the build still passes
(measured: `warning: Unmapped target property` + BUILD SUCCESSFUL) — every read of a persisted message then
silently misses it. `live` uses `IGNORE` for the opposite reason: its entity carries persist-only columns
with no domain counterpart, and that is stated in `LiveRoomMapper`.

**Deleting the attribute is not caught by the build**, because nothing is unmapped at the moment you delete
it. ArchUnit rule 20 (`mapstruct_mappers_must_fail_on_unmapped_targets`, repo-wide except `live`) closes that gap: it reads the
value out of the class file — `@Mapper` is `RetentionPolicy.CLASS`, so reflection cannot — and when the
attribute is gone it sees the annotation default `WARN`, so one rule covers both the downgrade to `IGNORE`
and the deletion.

Neither net is replaced by the round-trip test. Adding a domain field breaks every positional
`new ChatMessage(...)` call (13 of them, one of which is across the module boundary in `streaming-app`), and
the cheapest way to make those compile again is a `null` argument — which makes both sides null and the
round-trip assertion pass (measured: 4 round-trip cases green, only the test that walks
`getRecordComponents()` red). That walking test has its own blind spot: a primitive field lands on `0`,
which is not null, so only the policy catches it.
