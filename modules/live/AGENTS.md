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
| `enabled` | on — master switch; drops `@EnableScheduling` and the job beans |
| `<job>.enabled` · `<job>.cron` | on · staggered 10-min (`0/10`, `3/10`, `6/10` — keep them apart) |
| `expire-ready.threshold` · `end-stale-live.threshold` · `orphan-media.grace` | 60m · 60m · 15m |
| `expire-ready.batch-size` · `batch-size` | 20 (round-trips LiveKit per candidate) · 100 |

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
- Per-room work is a separate bean so `@Transactional` + row lock apply. **Single replica assumed** — add
  ShedLock before scaling out.
- **The room sweep applies no grace.** Only `Ended` rooms reach that point, and an `Ended` room can never
  have a legitimate SFU room (starting requires `Scheduled`). Grace would protect nothing while opening a
  hole: a seller re-joining recreates the room with a **fresh creation time**, so anyone reconnecting more
  often than the grace window would never be swept — which is the exact scenario this sweep exists for.
- **`Ended` rooms skip the ingress grace too.** The end transaction refreshes `updated_at`, so applying it
  would blind the job for a full grace window right after a crash between commit and cleanup — precisely
  when a surviving ingress lets the seller keep pushing.

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
