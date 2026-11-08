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
| `Scheduled` | `cancel()` (seller) | `Cancelled` | `canCancel()` — else LIVE-013 |
| `Scheduled` | `update()` (seller) | `Scheduled` | `canUpdate()` — else LIVE-014 |

- `Ended` and `Cancelled` are terminal (`LiveStatus.isTerminal()`). `expire()` and `cancel()` publish no `RoomEnded` — the room was never `Live`, so chat has no session.
- `cancel()` is `Scheduled`-only: `Ready` races the OBS-connect webhook, so it is left to the expire batch. LiveKit cleanup runs after commit (`PostCommitMediaCleanup`); leftovers are reclaimed by orphan reconciliation, which treats every terminal state alike — check `isTerminal()`, not `Ended`, there. Replay stays `Ended`-only.
- No transition INTO `Suspended` exists yet (record + exit only).
- `Suspended` counts as **not live** for external readers (`GetLiveRoomUseCase.live=false`) — 송출이 멈춘 방이므로 진행 중 통제(강퇴 등)의 판정 근거를 그렇게 제공한다. 거부 여부는 소비 도메인이 정한다. `startedAt` 은 다시보기 싱크용으로 보존한다. 뷰에서 `Ended` 와 구분되지 않으므로, `Suspended` 로 들어가는 전이를 만들 때 구분이 필요하면 그때 필드를 추가할 것.
- Adding a state = `sealed permits` + `LiveRoomMapper` switches + `LiveRoomStatus` enum + `LiveRoomRepositoryImpl.STATUS_COLUMN` + `LiveRoomTabs`, together.
- **A new `status` value is a rollout constraint.** No DDL is needed (no CHECK), but an old replica's `LiveRoomStatus` cannot read the new value — `findAllByIds` in the orphan job fails the whole round and room reads 500. Expose the transition that writes it (e.g. `cancel`, SPR-158) only after every replica runs the new code.
- Seller tabs (`LiveRoomTabs`) put `Suspended` under LIVE (the room has started — the seller's view), while external readers get `live=false`. Revisit when a transition INTO `Suspended` is added.

## Media ports — pick the failure direction

All LiveKit through `LiveMediaManager`. Never touch the SDK from a service.

**An answer that can destroy a broadcast fails loud; one that only withholds a promotion fails quiet.**

- Cleanup swallows (`deleteIngress`, `stopHlsEgress`, `stopEgress`, `closeRoom`) — leftovers are reconciliation's job.
- Global sweeps throw (`listAllIngress`, `listAllEgress`, `listAllRooms`); a misconfigured client answers
  `200 + []`, so **null body = failure** here.
- Per-room: `listRoomIngress` and `listRoomEgress` throw on HTTP/transport failure (feed destructive decisions);
  `publishingIngressIdsOrEmpty` returns empty on failure (feeds go-live only). A null ingress body is empty,
  but a null egress body is failure — no egress is represented by `[]`, and this answer can end a broadcast.
  `listRoomEgress` exists because the round-start `listAllEgress` snapshot is up to a whole round budget
  stale; judging a live broadcast on it kills rooms that reconnected mid-round.

**`playlist_name` and `live_playlist_name` must differ.** LiveKit rejects the egress request outright
(`invalid_argument: live_playlist_name cannot be identical to playlist_name`), so making them equal stops
every broadcast from starting — not just replay. They are also not interchangeable: `index.m3u8`
(live) is a sliding window that holds only the last few segments once the broadcast ends, while
`playlist.m3u8` (`EXT-X-PLAYLIST-TYPE:EVENT`) lists the whole broadcast and is what replay needs.
`HlsRendition.variantPlaylistPath()` and `MasterPlaylistGenerator.generate()` keep pointing at the live one.
The separate archive path travels via `HlsEgressResult` → `StreamInfo` → `LiveRoom.endLive`;
`hls_archive_url` preserves it from start through DB reload. ABR uses `archive-master.m3u8`, generated
with `generateArchive()`, whose variants are EVENT playlists. No publisher bean exists yet, so production
uses the 720p `playlist.m3u8` directly. Both masters must publish successfully or both URLs fall back to 720p.

**Replay is public**, with no signature, ownership check, or separate prefix. `GetLiveReplayUseCase`
serves `GET /api/v1/lives/rooms/{roomId}/replay`, separately from the authorization-only `GetLiveRoomUseCase`.
Persistence presents Ended legacy rows with `hls_archive_url = hls_url` as having no replay: the old end
path copied that exact value. `StreamInfo` retains the raw value for transitions; saving an Ended row
must preserve an existing equal-column value when the mapped archive is null. This exception is only
for legacy copies, not a general ban on writing null. This mapping uses no filename allowlist and preserves distinct
archive URLs verbatim, including query strings. It does not prove an arbitrary stored URL's media contents.
Remove it only after verified backfill of those rows (including rows ended by old app replicas) and complete
retirement of pre-SPR-148 writers. Do not auto-replace filenames or invent URLs for pre-upgrade live rooms.
Non-Ended rooms and Ended rooms without an archive return **404 LIVE-007**: the replay resource does not
exist, even if the room does. Ended is a DB state, not an egress completion guarantee; the last segments
and ENDLIST may still be finalizing immediately after end. S3 retention must keep the EVENT playlist
at least as long as its segments — see `infra/AGENTS.md`.

**Start-side media calls sit inside `@Transactional` on purpose** — reviewers must not flag them; `egressId`
has to commit with the room. LiveKit I/O is bounded by its client's `callTimeout` 15s per call.
That client does not bound `MasterPlaylistPublisher`: its future adapter must enforce a 15s total-call
timeout including retries and prove it with delayed-storage tests before registration. No publisher bean
exists today. Two sequential master publishes can consume up to 30s; 15s is not a whole-start budget.
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
| | the 3-min stagger only separates *short* rounds; budgets are 40m/20m/16m, so a backlogged round overlaps the other jobs and the pool has one thread per job on purpose (3 reconcile + 1 list refresh = 4) |
| `expire-ready.threshold` · `end-stale-live.threshold` · `orphan-media.grace` | 60m · 60m · 15m |
| `<job>.batch-size` | 20 / 10 · **orphan-media has none** (removed key; refuses to boot) |
| `<job>.lock-at-most-for` · `lock-at-least-for` | 50m / 25m / 20m · 1m (ShedLock; see the lock bullet) |

| Job | Candidate | Decides by | Acts via |
|---|---|---|---|
| `ReconcileExpiredReadyService` | `READY`, old `updated_at` | is **this room's** ingress publishing? | `goLiveByRtmp`, else `ExpireOrphanLiveUseCase` |
| `ReconcileStaleLiveService` | `LIVE`, old **`started_at`** | no active egress in LiveKit | `EndStaleLiveUseCase` (publishes `RoomEnded`) |
| `ReconcileOrphanMediaService` | every LiveKit room (its ingress/egress/SFU room) | mismatch against DB | delete by id / stop / `closeRoom` |

- Judge **per room, right before touching it** — a once-per-round snapshot expires rooms that reconnected.
- Expire-ready has **three** outcomes: promote / expire / **leave alone** (publishing, but not this room's).
- **A global sweep returning zero is not evidence.** `end-stale-live` aborts the round when the whole cluster
  reports no active egress. Cost: an idle cluster never gets swept — accepted, the other direction cuts live
  broadcasts. Don't drop the guard; verify per room instead.
- If the global snapshot marks a candidate active but its per-room list is empty, skip it for this round and
  record `SKIPPED_EGRESS_SNAPSHOT_MISMATCH`; partial routing/permission failures must not end a broadcast.
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
  `ReconcileLockConfig`; `ShedLockTableGuard` refuses to boot unless it can write to that table).
  A loser skips silently and retries next cycle. **Four boot guards refuse to start the app**, all
  fail-closed like `managementPortMustDiffer`: `ShedLockTableGuard` (can it actually write to the lock
  table), `legacyBatchSizeMustNotBeConfigured` (removed keys — `live.reconcile.batch-size` and
  `live.reconcile.orphan-media.batch-size` — must be gone from every deployed yaml/env before upgrading,
  or the app will not start), `leasesMustOutlastTheirCronPeriod` (a lease shorter than the tick interval
  means every tick finds it expired) and `lockIntervalsMustFitInCron` (`lock-at-least-for` must stay within half the shortest cron
  period of an enabled job — measured by expanding the cron over a day, since `0/N` gaps shrink at the
  hour boundary; over that, a round is skipped with no log and no counter). `<job>.lock-at-most-for` is the
  takeover delay after a holder dies, so it must exceed the job's worst round — ShedLock neither aborts nor
  extends a running round, so too short a lease means a slow round keeps going unlocked while the next tick
  starts a second one. Bounding a round by **candidate count** only works while the per-candidate
  fan-out is fixed, and it is fixed for none of the three: two go through `PostCommitMediaCleanup`, whose
  `stopHlsEgress`/`deleteIngress` fan out over the room's renditions and ingresses, and orphan-media stops
  *every* orphan egress of a room it selects. All three therefore bound the round by the **clock**:
  `roundBudget` = 4/5 of that job's own `lock-at-most-for` (25m→20m, 50m→40m, 20m→16m), and the candidate
  loop stops there, deferring the rest. It reports `ROUND_BUDGET_EXHAUSTED` (job tag says which), deliberately **not** folded
  into `*_BATCH_SATURATED` — one says *slow*, the other says *many*, and the fixes are opposite. The deadline
  is only checked *between* candidates (a synchronous OkHttp call can't be cancelled), so it limits new
  candidates but does **not** prove `lease ≥ budget + one candidate's fan-out`: a room's resource count has no
  code-level upper bound. A pathological room can therefore outlive its lease after the deadline; this design
  accepts that residual duplicate-cleanup risk to finish normal cleanup rather than leaving cost-bearing media
  behind. The lease default lives in **one** place per job (`LiveReconcileProperties.<Job>.DEFAULT_LOCK_AT_MOST_FOR`,
  concatenated into the `@SchedulerLock` placeholder) because the budget is derived from it: re-inline the
  literal in the scheduler and a lowered lease leaves the budget longer than the lease it was meant to fit
  inside. For orphan-media,
  `ORPHAN_ROOM_CANDIDATE` exposes the true backlog in **rooms** (it judges against the full LiveKit lists)
  and there is no saturation counter — the round has no count cap. `STALE_LIVE_CANDIDATE` is capped at
  `batch-size + 1`: enough to decide saturation, not a backlog gauge, and `STALE_LIVE_BATCH_SATURATED` is 1
  only when candidates remain after that cap.
  **`orphan-media` iterates by room, not by resource kind.** One room's ingress deletes → egress stops →
  `closeRoom` happen together, in that order, so AGENTS' "delete ingress room-wide before `closeRoom`" is
  structurally guaranteed instead of hoped for; a room whose cleanup the deadline cut short is left **open**
  for the next round. Per-kind `batch-size` and window rotation are gone with that split — they were what
  made "this room's ingress deferred, this room closed" expressible, and every window-starvation bug came
  from layering a window over the candidate ordering. The only stopping rule is `roundBudget` — which **does** cap
  the round, so tail starvation is still possible while the backlog exceeds what one round's budget gets
  through, and the candidate order is deterministic (terminal → `updated_at` → roomId). Rotation was dropped
  anyway because it cannot fix that: it reorders who waits, it does not raise throughput, and layering a
  window over the ordering is where every window-starvation bug came from. The signal to act on is
  `ORPHAN_ROOM_CANDIDATE` not falling across rounds — a persistently failing cleanup, or a budget too small
  for the backlog. Raise `lock-at-most-for` (the budget is 4/5 of it) rather than reintroducing a window.
  `live.reconcile.orphan-media.batch-size` is a **removed key that refuses to boot**, like
  `live.reconcile.batch-size`. Dropping the count cap also means the round's blast radius is bounded by the
  clock alone: a bad bulk UPDATE that marks many rooms `Ended` would have ~16 minutes of unrestricted
  deletion. That is accepted — a count cap could not tell a real backlog from a bad UPDATE either, and it
  cost the ordering invariant — so the compensating control is meant to be an alert on a
  spike in `SFU_ROOM_CLOSE_REQUESTED` / `INGRESS_DELETE_REQUESTED` rather than a cap. **That alert is not
  registered yet** — only the metrics exist (`reconcile.acted{job,action}`), and this repo holds no alert
  rules, so the rule lives wherever monitoring is configured. Until it does, the only real control is
  operational: before touching `live_room.status` in bulk, disable the job
  (`LIVE_RECONCILE_ORPHAN_MEDIA_ENABLED=false`). **That switch is `@ConditionalOnProperty`, i.e. bean
  registration — it needs a rolling restart and does not stop a round already running.** Plan the restart
  before the UPDATE, not after noticing one. End-stale-live remains FIFO and exposes `STALE_LIVE_CANDIDATE` plus
  `STALE_LIVE_BATCH_SATURATED`; it does not inherit the orphan rotation guarantee. Lease
  extension is avoided because it postpones takeover from a dead holder. **Only `orphan-media`
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
- **The room sweep applies no grace.** Only terminal (`Ended`/`Cancelled`) rooms reach that point, and a terminal room can never
  have a legitimate SFU room (starting requires `Scheduled`; nothing leaves a terminal state). Grace would protect nothing while opening a
  hole: a seller re-joining recreates the room with a **fresh creation time**, so anyone reconnecting more
  often than the grace window would never be swept — which is the exact scenario this sweep exists for.
- **Terminal rooms skip the ingress grace too.** The end/cancel transaction refreshes `updated_at`, so applying it
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
  so a round that loses the lock — or one where the lock store itself throws — raises no round counter.
  `live.reconcile.lock{job,result=acquired|skipped|shutdown|failed}` records that outer result instead. A normal
  shutdown waits 30s and then **releases nothing** — a round that finished has already unlocked itself in
  ShedLock's own `finally`, and taking the lock off one that is *still running* is exactly the mutual
  exclusion this lock exists for. `shutdownNow()` is merely an interrupt request and synchronous HTTP calls
  need not honor it, so a still-running round keeps its lock and hands over only when the 20m/25m/50m lease
  expires. Abnormal death follows the same
  lease-expiry path.
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
- Per-room egress lookup failures use `SKIPPED_EGRESS_LOOKUP_FAILED`, not plain `SKIPPED`, so LiveKit
  degradation is not buried under routine state-race skips. They do not abort the round; later candidates continue.
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
- **Live list = Redis, DB is the source.** `GET /rooms` reads Redis only (`LiveRoomCache`). Every transition
  into `Live` calls `LiveListCacheSync.registerAfterCommit`, every exit `removeAfterCommit` — **a new exit
  (e.g. into `Suspended`) must add the remove**. Store failures there are swallowed + counted on purpose
  (a throw in afterCommit fails an already-committed start); `RefreshLiveListService` (live-app, 15s, no
  ShedLock — idempotent) rewrites the list from DB and sets viewer scores via the `LiveViewerCounter` port,
  which live-app wires to chat-api (live-core must not depend on chat-api: chat already reads live-api,
  and ArchUnit rejects the cycle). Chat down → membership still fixed, scores keep their last value.
  Overlaps with a round cost one round either way (accepted race): an ended room can reappear, a just-started
  room can drop out. Over `MAX_LIVE_ROOMS` the round skips pruning and counts `live.list.refresh.saturated`.
- **Upcoming list** (`GET /upcoming`) is DB, `status IN (SCHEDULED, READY)` — inclusion only, so new states
  stay out. Cursor sort key is `scheduled_at` in epoch **micros** (column precision; millis breaks keyset ties).

## Enter rate limit — the signing ceiling

`enter` issues an RS256 token per call, so **every request costs a private-key signature**:
measured 685µs (RSA-2048) = ~1,458/s per core. (The webhook path is *not* a comparison — it does no
RSA verification; the LiveKit API secret is symmetric, which is why that path's own measurements
scale with body size.) Two population buckets cap it (`live.enter.rate-limit.guest-*` and
`.member-*`, defaults 500/s · burst 1000 each), with `member-shards` per-account buckets under the
member one sized to let the global stay binding, in `EnterRateLimitFilter`, inside the security chain
**after** the
JWT filter; a rejected call returns **429 with `Retry-After`** and never reaches the use case, so
neither the `findById` nor the signature is paid.

- **This is a CPU ceiling, not availability protection** — the same requirement the webhook limit
  carries, and **rps fairness belongs upstream**. The guest/member split covers one direction: a guest
  flood cannot starve logged-in viewers. The room owner has no reserved capacity, so a saturated
  member bucket blocks a seller from recovering the `owner` claim by reconnecting — bounded to the
  reconnect moment, since existing WS sessions survive.
- **Member traffic passes two gates with different jobs.** A shard bucket bounds **one account**;
  the **global bucket enforces the aggregate ceiling**. `member-shards` (default 64) fixed-size
  buckets are sized `ceil(global/shards)` — refill as is, burst times a headroom factor — so the
  shard sums *exceed* the global and the global is the binding constraint. That is what makes the
  configured number the real ceiling: with floor division the sums fell *below* it, and the defaults
  enforced **448/s · burst 960** while the config said 500/s · burst 1000.
  - **Shard first, then global.** Checking the global first lets a request the shard will reject burn
    a global token anyway, so one account could drain the global while being refused. The mirror
    image is real but smaller: a request rejected *by the global* has already spent its shard token.
  - **Burst does not pool across shards.** Splitting the burst means the binomial tail becomes 429s:
    with the burst split evenly, a 1,000-member reconnect storm — the demand vector this section
    itself names — lost **12.0%** (measured, 3,000 trials). The headroom factor is set at mean + ~4σ,
    where the same storm measures **0.0%**. The cost is that one account's one-shot rises to 32
    (3.2% of the global burst); its sustained share stays at the shard refill (8/s).
  - This bounds **blast radius, not fairness**: members hashed to the same shard are refused
    together (~1.6% at 64 shards). Covering every shard is possible but superlinear — coupon
    collector, **~301 accounts** for all 64, with 64 accounts reaching 63.5% coverage (measured).
- **The shard key carries a process-local salt** — without it the shard is **targetable**. The key is
  `sub` (= userId), `String.hashCode` is specified by the JDK, and the unauthenticated list response
  (`GetLiveView.LiveRoomSummary.sellerId`) hands out a live seller's userId. An attacker could
  compute that seller's shard offline and hold it empty with one account, which denies the seller the
  `owner` claim that chat's kick and unmasked-original gates depend on. With the salt, placement
  differs per instance; what remains is spraying, not aiming.
- **Not per-IP, and not an unbounded per-principal map.** Per-IP for the same reason as the webhook
  path (`X-Forwarded-For` is attacker-controlled → unbounded key space; `getRemoteAddr` collapses
  everyone onto one proxy IP). A per-principal **map** is the same memory-growth path — which is why
  this is a fixed-size **array**, bounded by construction *and* by an explicit `member-shards`
  ceiling (4096); without that ceiling a typo could pass validation and allocate an array that fails
  at allocation rather than at config validation.
- **Rejections are counted** — `live.enter.rejected{population="guest"|"member", gate="shard"|"global"}`.
  The `gate` tag exists because the two gates call for **opposite responses**: `shard` means one
  account exceeded its own share (go find it), `global` means the aggregate hit the ceiling (raise the
  limit or add capacity). Since the shard sums now exceed the global, most rejections under
  distributed load are `global`, so merging them would hide exactly the signal the shard exists for.
  Guest rejections are always `global` — guests have no shard.
  - Tag values are lowercase (`name().toLowerCase()`); querying the uppercase form returns *no
    series*, which is indistinguishable from no alert, so `PrometheusScrapeTest` pins the name and
    every tag string against real scrape output.
  - The limiter's log names its gate but is throttled per population, so one window can print only
    one of the two — read the split from the metric, not the log. Scale is what separates "attack"
    from "limit set too low"; the limit was derived from CPU cost, not from measured normal traffic.
- **The pool is a denominator, but the signature no longer holds a connection.** `EnterLiveService`
  `.enter` carries **no** `@Transactional` — the single `findById` runs in Spring Data's own read-only
  transaction and the connection is returned before `issue(...)` signs (685µs). Hikari's default max
  pool is **10** and no `maximum-pool-size` is set anywhere in this repo, so `findById` still shares
  that budget. If `enter` ever grows a second read, wrap **only the read**.
- **The limit does not throw.** `GlobalExceptionHandler` logs every 4xx, so throwing would emit one
  WARN per rejected request and let an attacker saturate log I/O; the limiter's own throttling only
  covers its own logger. The webhook filter avoids this the same way. The cost is that this 429
  carries **no `error.code`**, unlike every other error on the endpoint.
- **A controller must not assemble this.** `apps/AGENTS.md` forbids it and its raw-DTO exemption
  covers success bodies only — that is why the cap is a filter, not a branch in `enterRoom`.
- **`guest-permits-per-second` / `guest-burst` / `member-*` refuse to boot** when a limit is ≤0, a
  burst is below its permits, `member-shards` is outside `1..4096`, or
  `member-permits-per-second < member-shards` (the shard refills would then far exceed the global and
  the shard would bound nothing) — the same fail-closed shape as the reconcile guards.
- **Every bucket is instance-local** (population and shard alike), so the fleet limit is
  `limit × replicas`; there is none.
- **Recovery is a rolling restart** — the buckets are built once in the constructor and nothing here
  is `@RefreshScope`.
- The cap is on **issuance**, not on WS sessions: a room token is replayable within its TTL, so it
  does not bound `MAX_SESSIONS_PER_USER_PER_ROOM` fan-out in streaming-app.

## Room token — chat entry auth

`enter` issues an RS256 token; distinct key/alg/`aud` from the api-app HMAC token, never interchangeable.
Unauth viewers get an ephemeral GUEST token. Claims: `role`, `owner` (chat's PII gate), ~90s `exp`.
**email is PII** — never log the raw token. **Not single-use** — replayable within the TTL.

- **A revoked access token cannot get one.** `TokenRevocationFilter` consults both axes —
  `jti` (that one token) and `sid` (every token of that login session) — so logout, withdrawal and
  refresh-reuse detection (`JwtTokenLifecycle.revoke`/`revokeAll`) now block re-entry. `jti` is
  checked first and short-circuits, so a known-revoked token costs one round trip, not two.
  **Role demotion is not covered**, because no code path changes a role: it is fixed at creation
  (`UserEntity`'s two static factories, zero update writes). A path that demotes must revoke the
  token, or the claim is only as fresh as the token.
- **The window that remains is the token already issued.** Revocation gates *issuance*; it cannot
  recall a room token in flight, so the exposure is that token's `exp` plus — for an open WS
  session — whatever `ChatSessionRegistry` bounds. Before this filter the same window was the
  **access** TTL (3600s default), because entry could simply be re-taken.
- **It sits after the enter rate limit, before `AuthorizationFilter`** (`LiveSecurityConfig`).
  After the limit so a rejected entry pays no Redis round trip — the limit is a CPU ceiling and
  cannot absorb that cost. Before authorization so the SELLER/ADMIN rules (kick, ban release,
  dictionary) judge an already-cleared context. `LiveFilterOrderTest` pins the order itself and
  `TokenRevocationFilterTest` pins its consequence; both exist because either alone lets the other
  flip silently.
- **Confirmed revocation and a failed lookup are not the same answer.** Confirmed: the context is
  cleared and the chain continues — a role-gated route becomes 401, and a `permitAll` route
  **downgrades to guest**, which is correct (a revoked user who drops the header gets exactly what
  any stranger gets, so the power gained is zero). `RevocationOnPublicRouteTest` asserts that
  downgrade on purpose, so widening the fix to "stop both" cannot pass quietly.
- **A failed lookup returns 503 and stops the chain.** Clearing the context there would make the
  public enter route answer **200 + a GUEST room token** — `RevocationOnPublicRouteTest` pins that
  path with its confirmed-revocation case (cleared context → 200 + GUEST), and reverting the
  failure branch to "clear" breaks its sibling case. During a Redis outage a
  seller entering their own live would get `role=GUEST, owner=false`, losing chat voice and the
  unmasked-original view, with a 200 and no `error.code`, so neither the client nor the user has a
  signal. That is worse than 401, which is at least visible. The cost is that **a request carrying
  a token** is blocked during the outage; an unauthenticated one never reaches the lookup, so
  public viewing and guest entry stay up. `Retry-After` is a flat 5s — shorter would pile retries
  onto a struggling store, and the cause here is infrastructure, not the caller.
- **Both are counted**, because neither produces a distinctive response on its own:
  `live.token.revoked{axis="jti"|"sid"}` and `live.token.revocation.failed`. Separate *names*, not
  one name with an extra tag — a Prometheus meter requires one tag-key set per name, so merging
  them would silently drop whichever registers second. The axes call for opposite responses (`jti`
  is one token, routine after a re-issue; `sid` is a whole login session, so a spike means mass
  logout or token-theft response). Failure logs are throttled to one line per 10s with a running
  total, like the enter limiter — this path's volume is in the outage's hands, not an attacker's.
- **An active token costs two round trips** (`jti` then `sid`); the early return only saves one for
  an *already revoked* token. Ordering after the rate limit saves the rejected portion, not this.
- The checkers come from `common/auth`; only its refresh-token repository is kept out of the scan
  (`LiveAppApplication`), since this app still issues and rotates nothing.

## Errors & tests

Every domain exception maps to a `LiveErrorCode`; no raw `IllegalState`/`RuntimeException` escapes a service.
A state violation gets **one code per request kind** (start LIVE-003, end/expire 009, enter 010, ingress 011, cancel 013, update 014) via
`InvalidLiveStateException(code, msg)` — keep the single class, reconcile jobs catch it as "lost the race, skip".
Seller input rejected by the domain (prices 008, pin count 012) is `InvalidLiveProductException`, never IAE —
IAE reaches the global net as 500. A new request kind (cancel, edit, …) adds its own code; don't reuse 003.
Tests in `live-core/src/test` (Mockito on ports); scheduler wiring in `live-app` via `ApplicationContextRunner`.
FixtureMonkey for fixtures, plain builders when asserting specific values (random strings trip VO validation).
Run `./gradlew :modules:live:live-core:test`.
