# live — broadcast domain

Reference implementation for a new domain. Root `AGENTS.md` owns the cross-cutting rules; this file is
live-specific only. ✅ create / start (WebRTC·RTMP) / enter / end / list / reconciliation. 🚧 chat.

**Where the rest lives.** How, and why that belongs to one class, is in that class's comment and is not
repeated here. `.claude/rules/live/` holds, per area, a map of which class comments cover what (Claude Code
loads each only when a matching file is touched; other agents: read it before changing that area) plus the
few facts that fit neither here nor a comment.

| Area | Rules file |
|---|---|
| Orphan reconciliation — upgrade order, unregistered alert | `.claude/rules/live/reconcile.md` |
| Observability — metric semantics map | `.claude/rules/live/observability.md` |
| HLS egress artifacts & replay (`LIVE-08`) | `.claude/rules/live/replay-egress.md` |
| Enter rate limit & room token | `.claude/rules/live/enter.md` |

## Design-time invariants (check before spec/plan)

Path-scoped rules load only when a matching file is touched, so a spec or plan never sees them. Each fact
here is written once; the named class holds the reasoning.

- **Replay is public** — no signature, ownership check or separate prefix — and served by its own use case, never
  the authorization-only `GetLiveRoomUseCase` view (`GetLiveReplayUseCase`).
- **`MasterPlaylistPublisher` runs under the start row lock and is not bounded by `callTimeout`**; an adapter must
  cap the whole call, retries included, at 15s (`MasterPlaylistPublisher`).
- **ABR replay needs both masters published, or both URLs fall back to 720p** (`LiveKitMediaManager.publishMaster`).
- **`playlist_name` and `live_playlist_name` must differ** — LiveKit rejects the request, so every start fails (`LiveKitMediaManager`).
- **`Ended` is a DB state, not egress completion** — the last segments and ENDLIST may still be finalizing; S3
  retention must keep the EVENT playlist as long as its segments (`infra/AGENTS.md`).
- **Legacy archive rows stay** — remove the legacy-hiding mapping (`LiveRoomMapper.archiveUrl`) only after a verified
  backfill (incl. rows ended by old replicas) and full retirement of pre-SPR-148 writers; never auto-replace
  filenames or invent replay URLs for pre-upgrade rooms.
- **Before a bulk UPDATE of `live_room.status`, set `LIVE_RECONCILE_ORPHAN_MEDIA_ENABLED=false` and rolling-restart
  first** — it gates bean registration, so it neither applies without a restart nor stops a running round.
- **A round's `aborted` and `failed` outcomes never merge** (`live.reconcile.round{outcome}`; why: `LiveMetrics`).
- **Any role-demotion path must revoke tokens** (`JwtTokenLifecycle.revokeSession`/`revokeAllSessions`). None
  exists today — a role is fixed at creation (`UserEntity`).
- **Revocation gates issuance only** — an issued room token lives until `exp`, and its WS session as long as
  `ChatSessionRegistry` allows.
- **Inside the cache TTL a member gets the same room token; there is no `jti`** — the exposure window is unchanged,
  since the token was already replayable and the chat session cap (`chat.session.max-per-room`) is per user.
- **The enter cap is a CPU ceiling** — supply is per core, demand is fleet-wide; never compare them without a core count (`EnterRateLimitProperties`).
- **The seller enter lane's multi-account risk is accepted only because seller signup rejects duplicate business numbers** (`SellerAuthService`; why: `EnterRateLimitProperties`).
- **Accepted: one room can outlive its reconcile lease → duplicate cleanup** (`LiveReconcileProperties.roundBudget`).

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

- `Ended` and `Cancelled` are terminal (`LiveStatus.isTerminal()`) — code that means "finished" checks
  `isTerminal()`, not `Ended`; replay alone stays `Ended`-only. `expire()` and `cancel()` publish no `RoomEnded` —
  the room was never `Live`, so chat has no session.
- `cancel()` is `Scheduled`-only: `Ready` races the OBS-connect webhook, so it is left to the expire batch.
- `create()` and `update()` share `requireEditableFields`: `scheduledAt` must be after now (LIVE-015), so editing
  a `Scheduled` room whose time has passed means rescheduling it — intended (SPR-156).
- On end, `afterCommit` writes the ended key `live:room:ended:{roomId}` (TTL 70m) and publishes `RoomEnded` —
  both **at-most-once** (no retry, no outbox); accepted because the room is already `Ended` in the DB. Chat
  reads both. Why and the TTL's hand-copied lower bound → `RedisLiveEventPublisher`, `LiveRedisKeys`.
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
- Per-room: `listRoomIngress` / `listRoomEgress` throw (they feed destructive decisions);
  `publishingIngressIdsOrEmpty` returns empty (go-live only). Null-body handling per method and why
  `listRoomEgress` exists → `LiveMediaManager` Javadoc.
- HLS artifact names, archive URL and the public replay contract (`LIVE-08`) → `.claude/rules/live/replay-egress.md`.

**Start-side media calls sit inside `@Transactional` on purpose** — reviewers must not flag them; `egressId`
has to commit with the room. LiveKit I/O is bounded by the client's `callTimeout` (`LiveKitConfig`).
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

## Room thumbnail — presigned POST, server signs only

Seller asks `POST /api/v1/lives/thumbnails/uploads` → the browser POSTs the file straight to S3; bytes never pass
the app. Content (magic bytes) is checked **after** upload by an infra Lambda (separate ticket) that moves
`uploads/thumbnails/…` → `thumbnails/…` by **prefix swap only**, so the final URL is known at issue time. Until it
ships, `thumbnails/` stays empty and issued URLs 404 even though room create accepts them.

- LIVE-016 accepts only the requester's own CDN thumbnail path and never asks S3 whether it exists; update checks
  it only when the URL changes (`ThumbnailUrlRule`).
- **The signer supports static keys only** — moving to IRSA means replacing it. **Never log the policy, signature
  or keys** (`S3PostPolicySigner`).

## Orphan reconciliation — three `@Scheduled` jobs (live-app)

Triggers in `liveapp/scheduler` are thin; policy and loops live in `live-core`. Two of them move broadcasts:
`expire-ready` can **start** one, `end-stale-live` can **end** one that is still on air.
Config keys, ShedLock lease and round budget, boot guards, removed keys and the bulk-UPDATE procedure →
`.claude/rules/live/reconcile.md`. The scheduler pool is sized one thread per job, list refresh included (`SchedulingConfig`).

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
- **Don't change the ShedLock table's time columns to `timestamptz`** — why: `V202609051200__shedlock.sql`.
- **Terminal (`Ended`/`Cancelled`) rooms get no grace — neither the room sweep nor ingress deletion** — why:
  `ReconcileOrphanMediaService`.

## Observability — `LiveMetrics`

Ports-and-adapters, same as media: `application/port/LiveMetrics` (no micrometer — ArchUnit rule 16) ←
`infrastructure/metrics`. Metric semantics → `.claude/rules/live/observability.md`. Rules (each class's
comment says why):

- `LiveMetrics.NOOP` via `ObjectProvider`, **never `@ConditionalOnBean`** (`LiveMetricsConfig`).
- `MeteredLiveMediaManager` (`@Primary`) **must never swallow** — the port failure directions above are deliberate.
- **The metrics adapter never throws** (`MicrometerLiveMetrics.safe`, `MeteredLiveMediaManager.record`).
- **`management.server.port` must differ from the app port and stay cluster-internal**; the actuator chain is
  open only because of that (`managementPortMustDiffer`, `LiveSecurityConfig`). Never relax one without the other.

## LiveKit webhooks

`POST /webhooks/livekit`, `permitAll` — auth is the **body signature**, not Spring Security. Body is read
through a bounded stream, not `@RequestBody byte[]`.

- **Signature alone does not stop replay**; a `createdAt` window gates it — sizes and why: `LiveKitWebhookVerifier`.
- **A destructive handler (`egress_ended`, `room_finished`, …) requires event-id dedup in the same change.**
  Today's only handler is a no-op unless the room is `Ready+RTMP`, and that is what caps replay damage.
- Handlers belong to each feature, not the webhook package. **Must be idempotent.** Exceptions are logged and
  still return 200 — a throw does not trigger re-send.
- The rate limit is a **CPU ceiling, not availability protection** — nothing tells a forged request from a
  real one before verifying it. Keep it far above real traffic; lowering it lets an attacker starve
  `ingress_started` cheaply. rps limiting belongs upstream. Counter is per-instance.
  **Accepted: a dropped `ingress_started` is recovered only by `expire-ready`'s threshold**, so the room sits in
  `Ready` until then. Whether LiveKit re-sends after a 429 is not visible from this repo.

## Persistence & cache

- Schema `live_schema`, Flyway-owned (`db/migration/live/`). Entity mutable, record immutable.
- **Every state-transition read takes a row lock** (`findByIdForUpdate`, `findByIdAndSellerIdForUpdate`);
  unlocked reintroduces the double-`startHlsEgress` race. Ownership stays **in the query** — a service-side
  check would leak room existence. Hibernate emits **`for no key update`**, not `for update`; don't grep for
  the latter and conclude the lock is missing.
- `status`/`streamInfo`/`scheduledAt` are mapped by hand, not auto-mapped — why: `LiveRoomMapper`.
- **Live list = Redis, DB is the source.** `GET /rooms` reads Redis only (`LiveRoomCache`). Every transition into
  `Live` registers and every exit removes via `LiveListCacheSync` — **a new exit (e.g. into `Suspended`) must add
  the remove.** Store failures are swallowed + counted on purpose; `RefreshLiveListService` (15s, no ShedLock —
  idempotent) rewrites the list from the DB. Accepted: a round racing a transition costs one round either way.
- **live-core never depends on chat-api** — viewer counts come through the `LiveViewerCounter` port, wired in
  live-app (chat already reads live-api; ArchUnit rejects the cycle).
- **Upcoming list** (`GET /upcoming`) is DB, `status IN (SCHEDULED, READY)` — inclusion only, so new states stay
  out. Cursor key precision → `GetUpcomingLivesService`.
- **Recent views** (`GET /recent`, SPR-172): only a successful **member** entry is recorded, after the token is
  issued, and recording never fails or delays `enter` — drops are counted (`RecentViewRecorder`, `RecentViewConfig`).
  One row per (user, room), listed for 90 days.
  ⚠️ **Retention unresolved (follow-up ticket)**: viewing history is per-member behaviour data, yet neither
  >90-day rows nor withdrawn members' rows are ever deleted.

## Room token — chat entry auth

`enter` issues an RS256 token; distinct key/alg/`aud` from the api-app HMAC token, never interchangeable.
Unauth viewers get an ephemeral GUEST token. Claims: `role`, `owner` (chat's PII gate), ~90s `exp` — **capped at 300s** (refuses to boot above it; chat sizes its TTLs on that cap).
**email is PII** — never log the raw token. **Not single-use** — replayable within the TTL.
The enter rate limit (CPU ceiling, shards, salt, gates), the room-token cache and revocation (503 vs. guest
downgrade, filter order) → `.claude/rules/live/enter.md`. Read it before touching `enter`, its filters or the token.

## Errors & tests

Every domain exception maps to a `LiveErrorCode`; no raw `IllegalState`/`RuntimeException` escapes a service.
A state violation gets **one code per request kind** (start LIVE-003, end/expire 009, enter 010, ingress 011, cancel 013, update 014) via
`InvalidLiveStateException(code, msg)` — keep the single class, reconcile jobs catch it as "lost the race, skip".
Seller input rejected by the domain (prices 008, pin count 012) is `InvalidLiveProductException`; room input
(schedule not future 015, thumbnail URL 016) is `InvalidLiveRoomException` — never IAE —
IAE reaches the global net as 500. A new request kind (cancel, edit, …) adds its own code; don't reuse 003.
Tests in `live-core/src/test` (Mockito on ports); scheduler wiring in `live-app` via `ApplicationContextRunner`.
FixtureMonkey for fixtures, plain builders when asserting specific values (random strings trip VO validation).
Run `./gradlew :modules:live:live-core:test`.
