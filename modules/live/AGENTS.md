# live — broadcast domain

Live broadcast (seller streaming + viewer entry). **Reference implementation** — copy this structure for
a new domain. Root `AGENTS.md` owns the cross-cutting rules (hexagonal, immutable record + sealed status,
`TimeProvider`, validation); this file is **live-specific** only.

✅ broadcast: create / start (WebRTC·RTMP) / enter / end / get-list / orphan reconciliation — done & tested.
🚧 chat — not started. `infrastructure/` adapters: `media/` (LiveKit) · `persistence/` · `redis/` · `config/`.

## State Machine — `LiveStatus` (sealed)

`Scheduled → Live → Ended`; `Ready` is an RTMP-only waypoint, `Suspended` a side branch. State lives in
the record; transitions return a new `LiveRoom`; guards gate every one (never set status directly).

| From | Method | To | Guard |
|---|---|---|---|
| `create()` | — | `Scheduled` | — |
| `Scheduled` | `startLive()` (WebRTC) | `Live` | `canStartLive()` |
| `Scheduled` | `arm()` (RTMP) | `Ready` | `Scheduled` only |
| `Ready` | `goLiveFromReady()` (RTMP) | `Live` | `canGoLiveByRtmp()` |
| `Ready` | `expire()` (batch) | `Ended` | `canExpire()` |
| `Live` | `enter` (read) | — | `canEnterLive()` → hlsUrl |
| `Live`/`Suspended` | `endLive()` | `Ended` | `canEndLive()` |

- `expire()` is the batch-only exit for rooms OBS never connected to — the time threshold is batch policy,
  so the guard checks state only. No `RoomEnded` event (never was `Live` → no chat session to close).
- `Ready` carries only `scheduledAt` (reuses the `scheduled_at` column; `status` has no CHECK → adding a state needs no migration).
- `Suspended.startedAt`: `null` if suspended before broadcast, else `Live.startedAt`. **No transition INTO
  `Suspended` is built** (the record + `endLive()` exit exist; admin suspend is future work).
- Adding a state = update `sealed permits` + `LiveRoomMapper` switches + `LiveRoomStatus` enum together (the compiler forces the switches; no `default` branches).

## Media (SFU / HLS) — port-isolated

All LiveKit via `LiveMediaManager` (port) ← `LiveKitMediaManager` (adapter); never call the SDK from a
service. Surface: `createRoom`, `issueSellerToken`, `createIngress`, `isIngressActive` /
`listRoomIngress` (**same lookup, opposite failure direction — see below**), `startHlsEgress`,
`stopHlsEgress` (room-wide; a broadcast runs one egress per rendition, so there is no single-egress stop),
`deleteIngress` (room-wide / single-id), `closeRoom`, `getSfuUrl`, `listAllIngress`, `listAllEgress`.

**Cleanup calls are best-effort, query calls fail-fast.** `deleteIngress`/`stopHlsEgress`/`closeRoom` log
and move on (leftovers are reconciliation's job), but `listAllIngress`/`listAllEgress`/`listRoomIngress`
**throw** — an empty answer reads as "no orphans" / "not publishing" and would let a batch finish green, or
destroy a live room, on a failed lookup. `isIngressActive` is the fail-*open* twin (`false` on failure): safe
for the go-live rendezvous, wrong anywhere a `false` triggers deletion. Pick by which direction is destructive.
A successful response with a null body is **empty, not a failure** — conflating them kills the round on every
room that legitimately has no ingress.

**Start-side media calls run inside `@Transactional` on purpose (`StartLiveService`, `GoLiveByRtmpService`)
— reviewers must NOT flag these.** They run *after* re-validation so we never hit the server in a bad state,
and `egressId` has to commit with the room, so they can't move off the lock. Accepted risk: the connection
**and the row lock** are held across media I/O, bounded by `callTimeout` (15s **per call** in `LiveKitConfig`,
so `startHlsEgress`'s three sequential calls can hold ~45s). Capping the *waiting* side with a PostgreSQL
`lock_timeout` is still open — JPA's `jakarta.persistence.lock.timeout` hint is **not** it (PostgreSQL takes
only `NOWAIT`/`SKIP LOCKED`; a numeric wait is silently dropped), and `application*.yml` isn't tracked, so it
would have to be a `HikariConfig` bean. On rollback after `startHlsEgress`, `EgressRollbackCompensation`'s
`afterCompletion` hook stops it (delicate — the rollback-status check + intentional catch-log are load-bearing;
don't "fix" them).

**End-side cleanup runs *after* commit** (`PostCommitMediaCleanup`, shared by `EndLiveService`/
`ExpireOrphanLiveService`/`EndStaleLiveService`) — nothing there must commit with the room, so holding the
lock across it bought nothing. Leftovers from a crash between commit and cleanup are the orphan-media job's
to reclaim; that job existing is what made the move safe. Outside a transaction it cleans up immediately —
unlike `RoomEnded`, skipping isn't the safe default (egress keeps billing).

**Pinned product:** starting requires **exactly one** pinned product (`validatePinnedProduct`), both modes.

## RTMP (OBS / pro-encoder input)

WebRTC = browser token publish; RTMP = OBS pushes via LiveKit Ingress. Stream type is **not** fixed at
reservation (defaults `WebRtc`). **Go-live has two independently-ordered steps** — seller start (`arm` →
`Ready`) and OBS connect (`ingress_started` webhook → `GoLiveByRtmpService`); whichever lands **second**
triggers `Live`, so both sides are idempotent no-ops when the room isn't `Ready+RTMP`.

- **streamKey is a credential — never store or log.** Only `ingressId` persists; streamKey is returned
  **once** (re-fetch = LiveKit `listIngress`) and masked in `toString()`. Same reason `IngressSummary`
  carries neither `streamKey` nor `url`.
- `PrepareIngressService` keeps `createIngress` **outside any transaction**, so its `canPrepareIngress`/
  `isRtmp` checks are snapshot reads and can't be exclusive. Assignment is therefore a **conditional UPDATE**
  (`RtmpIngressAssigner` → `assignRtmpIngressIfAbsent`): `WHERE … live_status = SCHEDULED AND ingress_id IS
  NULL` makes the DB pick a winner, and the loser deletes **its own** ingress (single-id — a room-wide delete
  would take the winner's too). **This is the one path where the WHERE clause *is* the domain guard**; add a
  `LiveStatus` variant and the compiler won't remind you about this query. Don't spread the pattern —
  everywhere else, transitions serialize on a row lock. Same reason `IngressResult` validates `ingressId`
  itself: the UPDATE writes the column directly, so `LiveStreamType.Rtmp`'s constructor never sees it and a
  blank value would leave `stream_type=RTMP, ingress_id=NULL` — a row the mapper can no longer read.
- **End cleanup deletes ingress room-wide, before `closeRoom`** — a surviving ingress lets OBS auto-reconnect
  re-create the closed SFU room. Stale `ingress_id` stays on the Ended row by design (broadcast history).

## Orphan reconciliation — three `@Scheduled` jobs (live-app)

Rooms that never reach `endLive()` and LiveKit resources our cleanup missed. Triggers live in
`liveapp/scheduler` (thin — `reconcile()` only); policy, loops and per-item exception skipping live in
`live-core` services. Two of them move broadcasts, not just clean up: **`end-stale-live` can end one that is
still on air (the switch to pull first)** and **`expire-ready` can start one** (see below).

Config is split by layer, so no single class lists it all: `enabled`/`cron` are read by the schedulers
(live-app), `threshold`/`grace`/`batch-size` by `LiveReconcileProperties` (live-core). Everything has a code
default — `application*.yml` isn't tracked, so "unset" is the normal state.

| Key (under `live.reconcile`) | Default |
|---|---|
| `enabled` | on — master switch; off removes both `@EnableScheduling` and the job beans |
| `<job>.enabled` · `<job>.cron` | on · staggered 10-min cycles (`0/10`, `3/10`, `6/10` — don't align them) |
| `expire-ready.threshold` · `end-stale-live.threshold` | 60m |
| `orphan-media.grace` | 15m |
| `expire-ready.batch-size` | 20 — this job alone round-trips LiveKit **per candidate** |
| `batch-size` | 100, used by `end-stale-live` (one bulk `listAllEgress` per round) |

| Job | Candidate | Decides by | Acts via |
|---|---|---|---|
| `ReconcileExpiredReadyService` | `READY`, `updated_at` (= arm time) old | **ingress publishing?** — if yes the room is on air | publishing → `GoLiveByRtmpService`; else `ExpireOrphanLiveUseCase` |
| `ReconcileStaleLiveService` | `LIVE`, **`started_at`** old | **no active egress in LiveKit** | `EndStaleLiveUseCase` per room (publishes `RoomEnded`) |
| `ReconcileOrphanMediaService` | every LiveKit ingress/egress | mismatch against DB (read-only) | single-id delete / room-wide stop |

- **Ready-expiry can also *start* a broadcast.** A room whose `ingress_started` rendezvous was lost is still
  publishing; expiring it would delete the ingress and close the SFU room mid-stream, so the job completes
  the missed rendezvous instead. Judge **per room, right before touching it** (`listRoomIngress`) — a
  once-per-round snapshot lets a room that reconnects mid-round get expired anyway.

- **Ask the room, don't pick from the list.** `listRoomIngress` returns every ingress with a publishing
  flag, not a boolean, because a room can have two live ingresses (a race loser whose cleanup failed).
  Promotion requires the room to *acknowledge* one of them (`LiveRoom.hasIngress`), and that same id goes
  to `goLiveByRtmp` — the webhook entry point, not a second one. A batch-only "already checked, skip the
  match" entry is exactly how the guard goes missing on one side. Publishing-but-not-ours is a **third
  outcome**: neither promote (an unacknowledged ingress would start the broadcast) nor expire (it would cut
  a live stream) — skip, and the orphan-media job reclaims it (see the protection rule below; waiting for
  the room to reach `Ended` first would deadlock, since only this job could get it there).

- **Registered-but-idle ≠ unknown to LiveKit.** That's why the port hands back *all* ingresses instead of
  just the publishing ones. An RTMP room whose OBS never connected still has its ingress registered
  (`INACTIVE`) — the normal expiry target. An RTMP room whose DB row names an `ingress_id` that LiveKit
  reports **nothing** for means we're talking to the wrong cluster (a 200 + `[]` misconfig), and expiring
  that batch would cut live broadcasts. Filtering to publishing-only collapses both into an empty list and
  the distinction is gone. WebRtc rooms legitimately have none, so the guard is RTMP-only.

- **`BUFFERING` counts as publishing.** It's what a reconnecting OBS looks like; treating only
  `PUBLISHING` as live lets one unlucky snapshot drive a destructive decision.

- **A bulk lookup that returns 200 + `[]` is not evidence.** `listAllEgress` only throws on transport
  failure — a wrong host/apiKey answers empty, which reads as "every broadcast is dead" and would end the
  whole candidate batch, after which the orphan-media job deletes their still-live ingress 15 min later.
  So `end-stale-live` **aborts the round when no candidate-independent active egress exists at all**.
  Known cost: on a genuinely idle cluster (every broadcast's egress already stopped) stale rows are never
  swept — they keep accumulating until something else is live. Accepted because the failure is a stale DB
  row (the egress is already gone, so nothing is billing), while the other direction cuts live broadcasts.
  Don't "fix" it by dropping the guard; if the accumulation matters, verify per room instead.

- **Never judge staleness by viewer count** — HLS viewers are not SFU participants (a popular room reads 0),
  and a 0-viewer broadcast is normal. `started_at`, not `updated_at`: the latter moves on any save, so a
  broadcast that gets edited would never be swept.
- **When in doubt, don't delete.** A LiveKit resource with no DB row is logged only (`createIngress` done,
  `save` pending looks exactly like that), and a grace period covers the same window. A publishing ingress is
  spared too — **except when the room is already `Ended`**: that stream is a leftover of a failed end-cleanup,
  and this job is the only thing that would ever reclaim it (the seller can keep pushing with a streamKey
  they already hold, so egress keeps billing and the surviving ingress re-creates the closed SFU room).
- Per-room work is a separate bean so `@Transactional` + row lock actually apply (self-invocation would not).
  Orchestrators skip `InvalidLiveStateException`/`LiveNotFoundException` per item and let anything else abort
  the round.
- **Assumes a single live-app replica.** More replicas run every job N times — idempotent, so not incorrect,
  but wasteful; add ShedLock before scaling out.
- Ready-expiry's media cleanup overlaps `ReconcileOrphanMediaService` (it would sweep an `Ended` room's
  leftovers anyway). Kept for immediacy; drop it if this ever needs a bulk `UPDATE … RETURNING`.

## LiveKit Webhooks — receiver + handler contract

`POST /webhooks/livekit` (live-app) for events our API can't observe. Auth = **body signature**, not Spring
Security: the path is `permitAll` and `LiveKitWebhookVerifier` verifies the `Authorization` JWT over the raw
bytes, read via a **bounded stream** (not `@RequestBody byte[]`) so chunked bodies can't buffer unbounded.

**Signature alone does not stop replay** — the SDK's JWT check passes when `exp` is absent, so a captured
request stays valid forever. `createdAt` gates it: past 15m / future 60s, asymmetric on purpose (retries keep
the *original* `createdAt`, so the past side must survive a rolling deploy; the future side buys no defence
and only widens the attack window). Missing `createdAt` is **rejected** — verify one real staging event
before deploying, since a server that never sets it would block every webhook and strand rooms in `Ready`.

**Handlers are owned by each feature, not the webhook package** — a thin `@Component` trigger calling a
domain use-case; logic stays in the service. **MUST be idempotent** (LiveKit re-sends/replays).

**Adding a handler with a destructive effect (`egress_ended`, `room_finished`, …) requires event-id dedup
in the same change.** The 15m window narrows replay, it doesn't remove it — today's only handler
(`ingress_started`) is a no-op unless the room is `Ready+RTMP`, so a replay at worst does what OBS
connecting does. A handler that *ends* a broadcast has no such ceiling. The service
isolates + logs handler exceptions and still returns 200 (a throw does NOT trigger re-send) → loss-critical
work self-guards via retry/reconciliation.

## Persistence & Cache

- Schema `live_schema` (DDL `db/migration/live/`, Flyway-owned). Entity mutable, record immutable;
  `save()` upserts by `id` and mutation lives in `LiveRoomEntity` (`updateXxx`/`applyXxx`).
- **Every state-transition read takes a row lock** (`@Lock(PESSIMISTIC_WRITE)`, tx-only) — an unlocked one
  reintroduces the double-`startHlsEgress` race: `findByIdForUpdate` (webhook go-live, batch),
  `findByIdAndSellerIdForUpdate` (seller start/end). Ownership stays **in the query** — a service-side
  `sellerId` check would split "no such room" from "not yours" and leak room existence. Hibernate 7 +
  PostgreSQL emits **`for no key update`**, not `for update` (don't grep for the latter and conclude the
  lock is missing; two `for no key update` holders conflict, which is what serializes us). Lock-free
  `findById`/`findByIdAndSellerId` remain for read paths and `PrepareIngressService` (no tx → lock is a no-op).
- `LiveRoomMapper` (MapStruct) auto-maps flat fields; sealed `LiveStatus` ↔ `LiveRoomStatus` enum, variant
  fields and mutators go through `default`/`@AfterMapping`. Two fields are **restored from columns, not
  auto-mapped**: `status` and `streamInfo` (`toStreamInfo` → null when `sfu_room_id` is unset, since rows
  legitimately exist between the reservation save and `createRoom`). `scheduledAt` lives in the status, not
  top-level — `updateEntityFromDomain` sets `scheduled_at` **from the status** so a re-save doesn't wipe it.
- Ranking reads cache only: `GetLiveService` → `findTopByViewers` over Redis (`LiveRoomCache`), no DB; keys
  in `LiveRedisKeys` (package-private).

## Room Token — chat (streaming-app) entry auth

`enter` issues an **RS256 room token** (`live.room-token.private-key`, env-injected) — distinct key/alg/`aud`
from the api-app HMAC auth token, never interchangeable. Unauth viewers get an **ephemeral GUEST** token;
claims carry `role`, `owner` (`userId==sellerId`, chat's PII/notice gate) and a ~90s `exp`.

- **email is PII** (JWT signed, not encrypted): never log the raw token; chat must not pass it as a query
  param (access-log leak) — use a subprotocol / first frame.
- **Not single-use**: short TTL, no jti → replayable in-window; true one-time needs jti + SETNX on chat (out of scope).
- Undecided: `ADMIN` role source (api-app has only USER/SELLER); public key single static (v1) → `kid`+JWKS if rotation needed.

## Errors

Every domain exception maps to a `LiveErrorCode` (`LIVE-00x`; see the enum). No raw
`IllegalState`/`RuntimeException` escapes a service — infrastructure exceptions get translated at the adapter.

## Tests

`live-core/src/test` — service (Mockito on ports), media adapter, redis repo; fixtures via **FixtureMonkey**
(but plain builders where the test asserts on specific field values — FixtureMonkey's random strings trip
VO validation intermittently). Scheduler wiring is tested in `live-app` with `ApplicationContextRunner`.
Run `./gradlew :modules:live:live-core:test`.
