# live — broadcast domain

Live broadcast (seller streaming + viewer entry). **Reference implementation** — copy this structure for
a new domain. Root `AGENTS.md` owns the cross-cutting rules (hexagonal, immutable record + sealed status,
`TimeProvider`, validation); this file is **live-specific** only.

## Status

- ✅ broadcast: create / start (WebRTC·RTMP) / enter / end (incl. ingress cleanup) / get-list — done & tested.
- 🚧 chat — not started; add its conventions here when work begins.

`infrastructure/` adapters: `media/` (LiveKit) · `persistence/` · `redis/` · `config/`. Layout: `modules/AGENTS.md`.

## State Machine — `LiveStatus` (sealed)

`Scheduled → Live → Ended`; `Ready` is an RTMP-only waypoint, `Suspended` a side branch. State lives in
the record; transitions return a new `LiveRoom`; guards gate every one (never set status directly).

| From | Method | To | Guard |
|---|---|---|---|
| `create()` | — | `Scheduled` | — |
| `Scheduled` | `startLive()` (WebRTC) | `Live` | `canStartLive()` |
| `Scheduled` | `arm()` (RTMP) | `Ready` | `Scheduled` only |
| `Ready` | `goLiveFromReady()` (RTMP) | `Live` | `canGoLiveByRtmp()` |
| `Ready` | `expire()` (orphan cleanup) | `Ended` | `canExpire()` |
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
service. Surface: `createRoom`, `issueSellerToken`, `createIngress`, `isIngressActive`, `startHlsEgress`,
`stopHlsEgress`, `deleteIngress`, `closeRoom`, `getSfuUrl`, `listAllIngress`, `listAllEgress`.

**Cleanup calls are best-effort, query calls fail-fast.** `deleteIngress`/`stopHlsEgress`/`closeRoom` log
and move on (leftovers are reconciliation's job), but `listAllIngress`/`listAllEgress` **throw** — an empty
list reads as "no orphans" and would let the batch finish green on a failed lookup.

**Intentional in-`@Transactional` external calls (`StartLiveService`, `GoLiveByRtmpService`,
`EndLiveService`, `ExpireOrphanLiveService`) — reviewers must NOT flag these.** Start-side media calls run
in-tx *after* re-validation so we never hit the server in a bad state and `egressId` commits with the room.
Accepted risk: the connection **and the row lock** are held across media I/O for as long as OkHttp's
per-socket defaults allow (`LiveKitConfig` sets no `callTimeout`), so a concurrent transition on the same
room waits that out. Start-side calls can't leave the lock (`egressId` must commit with the room);
`callTimeout` + PostgreSQL `lock_timeout` are the follow-up — JPA's `jakarta.persistence.lock.timeout`
hint is **not** it (PostgreSQL takes only `NOWAIT`/`SKIP LOCKED`; a numeric wait is silently dropped). On rollback after
`startHlsEgress`, `EgressRollbackCompensation`'s `afterCompletion` hook stops it (delicate — the
rollback-status check + intentional catch-log are load-bearing; don't "fix" them). A process crash still
orphans the egress → reconciliation.
`EndLiveService`/`ExpireOrphanLiveService` differ: their cleanup calls produce nothing that must commit
with the room, so in-tx is convenience, not correctness — moving them to `afterCommit` is a known follow-up.

**Pinned product:** starting requires **exactly one** pinned product (`validatePinnedProduct`), both modes.

## RTMP (OBS / pro-encoder input)

WebRTC = browser token publish; RTMP = OBS pushes via LiveKit Ingress. Stream type is **not** fixed at
reservation (defaults `WebRtc`).

**Prepare** (`POST /rooms/{id}/ingress`, `PrepareIngressService`) — issue `rtmpUrl` + `streamKey`:
- **streamKey is a credential — never store or log.** Only `ingressId` persists (`LiveStreamType.Rtmp`);
  streamKey returned **once** (re-fetch = LiveKit `listIngress`); `IngressResult`/`IngressCredentialView`
  mask it in `toString()`. Same reason `IngressSummary` carries neither `streamKey` nor `url`.
- Guards: `Scheduled` (`canPrepareIngress`) + ownership (`findByIdAndSellerId`); idempotent — reject if
  already `isRtmp` (DB alone). Ingress binds to the room by roomId (LiveKit auto-creates it on OBS connect).

**Go-live** — two independently-ordered steps; whichever lands **second** triggers `Live`:
- **Start** (`POST /broadcast/start`): the RTMP branch of `StartLiveService` registers products + `arm`s
  to `Ready`, **no seller token**. If ingress is already `PUBLISHING` (`isIngressActive`), goes `Live` now.
- **OBS connect**: `ingress_started` webhook → `IngressStartedWebhookHandler` (parses `roomName`=roomId)
  → `GoLiveByRtmpService`. Idempotent: non-`Ready+RTMP` (still `Scheduled`, already `Live`, WebRTC) is a
  **no-op**; room found by **roomId alone** (ownership checked earlier). **Concurrent** arrivals serialize on
  a row lock (`findByIdForUpdate`) — the later one reads `Live` and no-ops, so no double `startHlsEgress`.
  `LiveKitWebhookVerifier` reads `roomName` from `room`, falling back to `ingressInfo.roomName`.

**End cleanup** (`EndLiveService`): RTMP rooms delete ingress on end — **room-wide** (`deleteIngress(roomId)`
lists by roomName and deletes all, sweeping double-`prepare` orphans too), best-effort (failure never blocks
the end tx → reconciliation), ordered **before `closeRoom`** (a surviving ingress lets OBS auto-reconnect
re-create the closed SFU room). Stale `ingress_id` stays on the Ended row by design (`Rtmp` forbids blank;
it's broadcast history).

**Follow-ups (not built):** orphan-ingress/egress reconciliation batch — covers rooms that never reach
`endLive()`: `Ready` stuck (OBS never connected; `canEndLive` false so no cancel path), `Scheduled` with
prepared ingress, process crash mid-start.

## LiveKit Webhooks — receiver + handler contract

`POST /webhooks/livekit` (live-app) for events our API can't observe (`room_finished`, `ingress_started`,
`egress_ended`, …). Auth = **body signature**, not Spring Security: path is `permitAll`, `WebhookVerifier`
(← `LiveKitWebhookVerifier`) verifies the `Authorization` JWT. Body via **bounded streaming read** (not
`@RequestBody byte[]`) so chunked can't buffer unbounded (memory DoS); raw UTF-8 bytes pass through for the
signature. `LiveWebhookService` verifies then dispatches to every `LiveWebhookHandler.supports(type)`.

**Handlers are owned by each feature, not the webhook package** — a **thin trigger adapter** (`@Component`,
auto-registered) calling a domain use-case; logic stays in the service. **MUST be idempotent** (LiveKit
re-sends/replays). The service **isolates + logs** handler exceptions and still returns 200 (a throw does
NOT trigger re-send) → loss-critical work self-guards via retry/reconciliation.

## Persistence & Cache

- Schema `live_schema` (DDL `db/migration/live/`, Flyway-owned).
- Entity mutable, record immutable. `LiveRoomRepositoryImpl.save()` upserts by `id`: null → insert; else
  load + `LiveRoomMapper.updateEntityFromDomain` + save. Mutation lives in `LiveRoomEntity` (`updateXxx`/`applyXxx`).
- **Every state-transition read takes a row lock** (`@Lock(PESSIMISTIC_WRITE)`, tx-only) — an unlocked one
  reintroduces the double-`startHlsEgress` race: `findByIdForUpdate` (webhook go-live, orphan expiry),
  `findByIdAndSellerIdForUpdate` (seller start/end). Ownership stays **in the query** — a service-side
  `sellerId` check would split "no such room" from "not yours" and leak room existence. Hibernate 7 +
  PostgreSQL emits **`for no key update`**, not `for update` (don't grep for the latter and conclude the
  lock is missing; two `for no key update` holders conflict, which is what serializes us). Lock-free
  `findById`/`findByIdAndSellerId` remain for read paths (`enter`, list) and `PrepareIngressService`
  (no tx by design → a lock there would be a no-op).
- `LiveRoomMapper` (MapStruct, `componentModel=spring`) auto-maps flat fields; sealed `LiveStatus` ↔
  `LiveRoomStatus` enum, variant fields, and mutators go through `default`/`@AfterMapping`. Note:
  `scheduledAt` lives in the status, not top-level — `updateEntityFromDomain` sets `scheduled_at` **from
  the status** so a re-save doesn't wipe it.
- Ranking reads cache only: `GetLiveService` → `findTopByViewers` over Redis (`LiveRoomCache`), no DB; keys
  in `LiveRedisKeys` (package-private).

## Room Token — chat (streaming-app) entry auth

`enter` issues an **RS256 room token** (private key `live.room-token.private-key`, env-injected) — distinct
key/alg/`aud` from the api-app HMAC auth token, never interchangeable. Issued in `EnterLiveService` after
confirming the room is live (`RoomTokenIssuer` ← `RoomTokenProvider`); members get identity tokens, unauth
viewers an **ephemeral GUEST** token.

- Claims: `iss=live`, `aud=chat`, `sub=userId`, `room`, `role` (USER→BUYER / SELLER→SELLER / unauth→GUEST),
  `owner` (`userId==sellerId`, chat's PII/notice gate), `nickname`/`email` members only, `exp` (~90s).
- **email is PII** (JWT signed, not encrypted): never log the raw token; chat must not pass it as a query
  param (access-log leak) — use a subprotocol / first frame.
- **Not single-use**: short TTL, no jti → replayable in-window; true one-time needs jti + SETNX on chat (out of scope).
- `live-app` principal: `LiveUserPrincipal` (userId·role·nickname·email), `getName()=userId` keeps `CurrentUserIdArgumentResolver` working.
- Undecided: `ADMIN` role source (api-app has only USER/SELLER); public key single static (v1) → `kid`+JWKS if rotation needed.

## Errors

Throw domain exceptions → each maps to a `LiveErrorCode`: `LIVE-001` media (`LiveMediaException`), `-002`
not-found (`LiveNotFoundException`), `-003` invalid-state (`InvalidLiveStateException`), `-004`
unsupported-role (`UnsupportedRoleException`), `-005` invalid-webhook (`InvalidWebhookException`), `-006`
broadcast-start (`BroadcastStartException`, tx-sync guard). No raw `IllegalState`/`RuntimeException` from
services.

## Tests

`live-core/src/test` — service (Mockito on ports), media adapter, redis repo; fixtures via **FixtureMonkey**.
Run `./gradlew :modules:live:live-core:test`.
