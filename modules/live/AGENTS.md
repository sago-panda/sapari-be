# live — broadcast domain

Reference implementation for a new domain. Root `AGENTS.md` owns the cross-cutting rules; this file is
live-specific only. ✅ create / start (WebRTC·RTMP) / enter / end / list / reconciliation. 🚧 chat.

## State machine — `LiveStatus` (sealed)

State lives in the record; transitions return a new `LiveRoom`; a guard gates every one.

| From | Method | To | Guard |
|---|---|---|---|
| `create()` | — | `Scheduled` | — |
| `Scheduled` | `startLive()` (WebRTC) | `Live` | `canStartLive()` |
| `Scheduled` | `arm()` (RTMP) | `Ready` | `Scheduled` only |
| `Ready` | `goLiveFromReady()` | `Live` | `canGoLiveByRtmp()` |
| `Ready` | `expire()` (batch) | `Ended` | `canExpire()` |
| `Live` | `enter` (read) | — | `canEnterLive()` |
| `Live`/`Suspended` | `endLive()` | `Ended` | `canEndLive()` |

- `Ended` is terminal. `expire()` publishes no `RoomEnded` — the room was never `Live`, so chat has no session.
- No transition INTO `Suspended` exists yet (record + exit only).
- `Suspended` counts as **not live** for external readers (`GetLiveRoomUseCase.live=false`) — 송출이 멈춘 방이므로 진행 중 통제(강퇴 등)의 판정 근거를 그렇게 제공한다. 거부 여부는 소비 도메인이 정한다. `startedAt` 은 다시보기 싱크용으로 보존한다. 뷰에서 `Ended` 와 구분되지 않으므로, `Suspended` 로 들어가는 전이를 만들 때 구분이 필요하면 그때 필드를 추가할 것.
- Adding a state = `sealed permits` + `LiveRoomMapper` switches + `LiveRoomStatus` enum, together.

## Media ports — pick the failure direction

All LiveKit through `LiveMediaManager`. Never touch the SDK from a service.

**An answer that can destroy a broadcast fails loud; one that only withholds a promotion fails quiet.**

- Cleanup swallows (`deleteIngress`, `stopHlsEgress`, `closeRoom`) — leftovers are reconciliation's job.
- Global sweeps throw (`listAllIngress`, `listAllEgress`, `listAllRooms`); a misconfigured client answers
  `200 + []`, so **null body = failure** here.
- Per-room: `listRoomIngress` throws (feeds deletion), `publishingIngressIdsOrEmpty` returns empty on failure
  (feeds go-live only). **Null body = empty** — rooms legitimately have no ingress.

**Start-side media calls sit inside `@Transactional` on purpose** — reviewers must not flag them; `egressId`
has to commit with the room. The row lock is held across media I/O, bounded by `callTimeout` 15s per call.
**End-side cleanup runs after commit** (`PostCommitMediaCleanup`), safe only because the orphan-media job
reclaims crash leftovers.

Starting requires **exactly one** pinned product, both modes.

## RTMP (OBS input)

Stream type is not fixed at reservation (defaults `WebRtc`). **Go-live has two independently-ordered steps**:
seller `arm` → `Ready`, and OBS connect → `ingress_started` webhook. Whichever lands second triggers `Live`,
so both sides must reach the same outcome — if they diverge, the room's fate depends on arrival order.

- **streamKey is a credential.** Never stored, never logged, returned once.
- **Promotion requires the room to acknowledge the ingress** (`LiveRoom.hasIngress`) — webhook, batch and
  seller-start all match. A room can hold two live ingresses (a race loser whose delete failed); promoting on
  an unacknowledged one starts a broadcast the orphan-media job then cuts.
- `createIngress` runs outside any transaction, so `PrepareIngressService`'s guards are snapshot reads and
  assignment is a conditional UPDATE (`assignRtmpIngressIfAbsent`). **The one place where a WHERE clause is
  the domain guard** — add a `LiveStatus` variant and the compiler won't remind you. Don't spread it.
- End cleanup deletes ingress room-wide **before** `closeRoom`; a survivor lets OBS re-create the room.

## Orphan reconciliation — three `@Scheduled` jobs (live-app)

Triggers in `liveapp/scheduler` are thin; policy and loops live in `live-core`. Two of them move broadcasts:
`expire-ready` can **start** one, `end-stale-live` can **end** one that is still on air.

| Key (`live.reconcile`) | Default |
|---|---|
| `enabled` | on — master switch; drops `@EnableScheduling`, the job beans and the lock config |
| `<job>.enabled` · `<job>.cron` | on · staggered 10-min (`0/10`, `3/10`, `6/10` — keep them apart) |
| `expire-ready.threshold` · `end-stale-live.threshold` · `orphan-media.grace` | 60m · 60m · 15m |
| `expire-ready.batch-size` · `batch-size` | 20 (round-trips LiveKit per candidate) · 100 |
| `<job>.lock-at-most-for` · `lock-at-least-for` | 45m / 90m / 60m · 1m (ShedLock; see the lock bullet) |

| Job | Candidate | Decides by | Acts via |
|---|---|---|---|
| `ReconcileExpiredReadyService` | `READY`, old `updated_at` | is **this room's** ingress publishing? | `goLiveByRtmp`, else `ExpireOrphanLiveUseCase` |
| `ReconcileStaleLiveService` | `LIVE`, old **`started_at`** | no active egress in LiveKit | `EndStaleLiveUseCase` (publishes `RoomEnded`) |
| `ReconcileOrphanMediaService` | every LiveKit ingress/egress/room | mismatch against DB | delete by id / stop / `closeRoom` |

- Judge **per room, right before touching it** — a once-per-round snapshot expires rooms that reconnected.
- Expire-ready has **three** outcomes: promote / expire / **leave alone** (publishing, but not this room's).
- **A global sweep returning zero is not evidence.** `end-stale-live` aborts the round when the whole cluster
  reports no active egress. Cost: an idle cluster never gets swept — accepted, the other direction cuts live
  broadcasts. Don't drop the guard; verify per room instead.
- **`BUFFERING` counts as publishing** — that is a reconnecting OBS.
- **A publishing ingress is spared only while the room acknowledges it** — unacknowledged ones are reclaimed
  mid-publish. Waiting for `Ended` instead would deadlock: only this job can get the room there.
- **The seller's LiveKit token (6h, unrevocable) can recreate a closed room.** It has no ingress and no egress,
  so only `listAllRooms` sees it. Judge on **DB status only** — gating on participant count spares exactly the
  case this targets. A shorter TTL is not a substitute; it needs a refresh endpoint first.
- **Never judge staleness by viewer count** — HLS viewers are not SFU participants. `started_at`, not `updated_at`.
- **When in doubt, don't delete.** No DB row → log only; grace covers the create-then-save window.
- Per-room work is a separate bean so `@Transactional` + row lock apply. **Multi-replica safe**: each job
  holds its own ShedLock lock (`live-reconcile-<job>`, JDBC provider on `live_schema.shedlock`,
  `ReconcileLockConfig`; `ShedLockTableGuard` refuses to boot if the migration has not run).
  A loser skips silently and retries next cycle; `<job>.lock-at-most-for` is the
  takeover delay after a holder dies, so it must exceed the job's worst round — ShedLock neither aborts nor
  extends a running round, so too short a lease means a slow round keeps going unlocked while the next tick
  starts a second one. `end-stale-live` is the **longest** job, not the shortest: it uses the shared
  `batch-size` 100 and every ended room runs `PostCommitMediaCleanup`'s up-to-3 LiveKit calls synchronously
  on the round thread. `orphan-media` has no batch bound at all, so no fixed lease is provably enough —
  bounding that sweep is the real fix and is not in SPR-142. **Only `orphan-media`
  actually needs it** — the other two decide, lock the row, and register cleanup inside the winning
  transaction, so the *destructive* calls happen once regardless (the read sweeps still duplicate per
  replica). `orphan-media` has no DB gate at all, and cleanup
  ports swallow, so a duplicate round is invisible except as doubled `reconcileActed` counts — which
  breaks the "same numbers repeating = nothing is being cleaned" reading.
- **The lock table's time columns are `timestamp`, not `timestamptz`** — ShedLock writes *and* compares
  `timezone('utc', CURRENT_TIMESTAMP)`, a zone-less value. In a `timestamptz` column that is resolved
  against the session zone, which pgjdbc takes from each JVM's default — measured, the same instant lands
  32400s apart from a UTC vs. a KST session, so replicas in different zones stop excluding each other.
  `timestamp` has no such path. Don't "fix" it to match the rest of the schema.
- **The room sweep applies no grace.** Only `Ended` rooms reach that point, and an `Ended` room can never
  have a legitimate SFU room (starting requires `Scheduled`). Grace would protect nothing while opening a
  hole: a seller re-joining recreates the room with a **fresh creation time**, so anyone reconnecting more
  often than the grace window would never be swept — which is the exact scenario this sweep exists for.
- **`Ended` rooms skip the ingress grace too.** The end transaction refreshes `updated_at`, so applying it
  would blind the job for a full grace window right after a crash between commit and cleanup — precisely
  when a surviving ingress lets the seller keep pushing.

## Observability — `LiveMetrics`

Ports-and-adapters, same as media: `application/port/LiveMetrics` (no micrometer) ← `infrastructure/metrics`.
ArchUnit rule 16 fails the build if `domain`/`application` imports `io.micrometer`. `LiveMetrics.NOOP` is
selected at runtime via `ObjectProvider` when no `MeterRegistry` exists — **not** `@ConditionalOnBean`, which
evaluates before autoconfiguration and would silently disable all metrics.

- **Transitions and promotions are counted after commit** (the adapter registers a synchronization). Inside
  a tx the transition may still roll back — `StartLiveService` saves products *after* the room.
- **Round counters fire even on a 0-candidate round.** No record must mean "the scheduler is dead", not
  "nothing to do", or a dead job is indistinguishable from a quiet one.
- **The distributed lock punched a hole in that rule.** `@SchedulerLock` wraps `run()` from the *outside*,
  so a round that loses the lock — or one where the lock store itself throws — raises no round counter and
  no domain log at all. Cluster-wide the totals still come to one round per tick, but a dead holder leaves
  its job with **zero records for a whole lease**, which reads exactly like a dead scheduler. Fixing it
  needs a counter around the lock, i.e. a new `LiveMetrics` port method; until then this is a known
  blind spot, not an oversight.
- **A round ends exactly one way: `completed` / `aborted` / `failed`** (`outcome` tag on
  `live.reconcile.round`). `aborted` is the guard folding the round on its own; `failed` is an exception
  escaping to the scheduler. Both look like "completed didn't rise" from outside but need opposite
  responses — check the config vs. chase the exception — so they must never be merged.
- `reconcileRoundAborted` is **round-scoped** — only `end-stale-live`'s cluster-wide guard raises it, and it
  is the only external signal that a guard swallowed a round (those paths are a bare `return`). A per-room
  verdict must never raise it: it would fire up to batch-size times in one round and break
  `aborted + completed = rounds`. `expire-ready`'s "LiveKit doesn't know this room's ingress" is therefore
  `ReconcileAction.SKIPPED_INGRESS_MISSING`, split out of plain `SKIPPED` so the misconfiguration signal
  isn't buried under routine skips.
- **`orphan-media` deliberately has no abort guard.** All three lists empty is the normal state on a quiet
  night; counting it as `aborted` would alarm daily until someone mutes the alarm. It owns the
  `live.livekit.egress.rooms` gauge instead — it sweeps unconditionally every round, so a misconfigured
  `200 + []` shows up as the gauge dropping to 0 while DB-side `live.room.active` holds.
- `MeteredLiveMediaManager` wraps `LiveKitMediaManager` as `@Primary`. **It must never swallow** — each port
  method's failure direction is deliberate (see Media ports); swallowing one disarms the reconcile guards.
- `PromotionTrigger` has three values because there are three go-live paths (`SELLER_START` rendezvous,
  `WEBHOOK`, `RECONCILE`). A new entry point gets a compile error, not a silent metric gap.
- `live.room.active` (DB `COUNT`, per scrape) is meant to be read **next to** `live.livekit.egress.rooms`
  (last round's observation, `-1` = never observed). The two diverging is the shared symptom of live outages.
  `end-stale-live` also refreshes the gauge, but only past its 0-candidate return — never rely on that one.
- **Exposure: `management.server.port` is separate (template default 8091 — the app itself runs on 8081) and must stay cluster-internal.** Metrics
  carry business volume and the gauge hits the DB per scrape, so on the user port an anonymous scraper reads
  the numbers *and* competes for the pool that starts broadcasts.
- **Splitting the port does not split security.** The management context inherits the filter chain, so
  without a dedicated chain the scraper gets 401 and metrics silently stay empty — indistinguishable from
  "no broadcasts". `LiveSecurityConfig.actuatorFilterChain` (`@Order(0)`, `EndpointRequest.toAnyEndpoint()`)
  permits them, and that opening is safe **only** because the ports differ — which
  `managementPortMustDiffer` enforces by refusing to boot. Never relax one of the two without the other.
- **The round wrapper catches nothing.** It marks a completed flag on the normal path and counts `failed`
  from `finally`, so an `Error` is counted like any other abnormal exit while never being caught — the
  earlier "catch `RuntimeException` vs `Throwable`" dilemma disappears. Same trick in
  `MeteredLiveMediaManager.timed`; prefer it over widening a catch.
- **The metrics adapter never throws** (`MicrometerLiveMetrics.safe`, `MeteredLiveMediaManager.record`).
  Two paths make this business-critical, not tidiness: a throw from an `afterCommit` hook propagates to
  the commit caller, so an already-committed `Ready→Live` would surface to the seller as a failed start;
  and `reconcileRoundFailed` is called right before a rethrow, so a throw there replaces the real cause.
- **`live.livekit.egress.rooms` counts rooms, not egress records** — one broadcast runs several egresses
  (renditions), so record counts are a multiple of room counts and could not be read beside
  `live.room.active`. Both writers (`orphan-media`, `end-stale-live`) parse and distinct the room id.

## LiveKit webhooks

`POST /webhooks/livekit`, `permitAll` — auth is the **body signature**, not Spring Security. Body is read
through a bounded stream, not `@RequestBody byte[]`.

- **Signature alone does not stop replay** (the SDK accepts a missing `exp`). `createdAt` gates it: past 15m,
  future 60s — asymmetric because retries keep the original `createdAt` and must survive a rolling deploy.
- **A destructive handler (`egress_ended`, `room_finished`, …) requires event-id dedup in the same change.**
  Today's only handler is a no-op unless the room is `Ready+RTMP`, and that is what caps replay damage.
- Handlers belong to each feature, not the webhook package. **Must be idempotent.** Exceptions are logged and
  still return 200 — a throw does not trigger re-send.
- The rate limit is a **CPU ceiling, not availability protection** — nothing tells a forged request from a
  real one before verifying it. Keep it far above real traffic; lowering it lets an attacker starve
  `ingress_started` cheaply. rps limiting belongs upstream. Counter is per-instance.
  **A dropped `ingress_started` is not recovered quickly**: the fallback is `expire-ready`, which only picks
  up rooms older than its 60m threshold — so the room can sit in `Ready` for 60–70 minutes. Whether LiveKit
  re-sends after a 429 is not visible from this repo and decides how often that actually happens.

## Persistence & cache

- Schema `live_schema`, Flyway-owned (`db/migration/live/`). Entity mutable, record immutable.
- **Every state-transition read takes a row lock** (`findByIdForUpdate`, `findByIdAndSellerIdForUpdate`);
  unlocked reintroduces the double-`startHlsEgress` race. Ownership stays **in the query** — a service-side
  check would leak room existence. Hibernate emits **`for no key update`**, not `for update`; don't grep for
  the latter and conclude the lock is missing.
- `LiveRoomMapper`: `status`/`streamInfo` come from columns, not auto-mapping; `scheduledAt` lives inside the
  status, so `updateEntityFromDomain` must write it from there or a re-save wipes it.
- Ranking reads Redis only (`LiveRoomCache`), no DB.

## Room token — chat entry auth

`enter` issues an RS256 token; distinct key/alg/`aud` from the api-app HMAC token, never interchangeable.
Unauth viewers get an ephemeral GUEST token. Claims: `role`, `owner` (chat's PII gate), ~90s `exp`.
**email is PII** — never log the raw token. **Not single-use** — replayable within the TTL.

## Errors & tests

Every domain exception maps to a `LiveErrorCode`; no raw `IllegalState`/`RuntimeException` escapes a service.
Tests in `live-core/src/test` (Mockito on ports); scheduler wiring in `live-app` via `ApplicationContextRunner`.
FixtureMonkey for fixtures, plain builders when asserting specific values (random strings trip VO validation).
Run `./gradlew :modules:live:live-core:test`.
