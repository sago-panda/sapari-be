# live — broadcast domain

Live broadcast domain (seller streaming + viewer entry). The **reference implementation** —
copy this structure when starting a new domain. Root `AGENTS.md` covers the cross-cutting rules
(hexagonal layout, immutable record + sealed status, `TimeProvider`, validation responsibility);
this file is the **live-specific** detail only.

## Status

- ✅ broadcast: create / start / enter / end / get-list — implemented & tested.
- 🚧 chat — not started. Add chat code + its conventions here when work begins.

## Package Layout

Standard domain-module layout — see `modules/AGENTS.md`.
Live's `infrastructure/` adapters: `media/` (LiveKit) · `persistence/` · `redis/` · `config/`.
Key classes are named inline in the sections below.

## State Machine — `LiveStatus` (sealed)

`Scheduled → Live → Ended`, with `Suspended` as a side branch. State lives in the domain record;
transitions return a new `LiveRoom`. Guards gate every transition — never set status directly.

| From | Method | To | Guard |
|---|---|---|---|
| `create()` | — | `Scheduled` | — |
| `Scheduled` | `startLive()` | `Live` | `canStartLive()` |
| `Live` | `enter` (read) | — | `canEnterLive()` (returns hlsUrl) |
| `Live` / `Suspended` | `endLive()` | `Ended` | `canEndLive()` |

- `Suspended.startedAt` is `null` if suspended **before** broadcast, else carries `Live.startedAt`.
- Adding a state = update the `sealed permits`, `LiveRoomMapper` switches, and `LiveRoomStatus` enum together (the compiler forces the mapper switches; don't add `default` branches).

## Media (SFU / HLS) — port-isolated

All LiveKit access goes through `LiveMediaManager` (port) ← `LiveKitMediaManager` (adapter).
Never call LiveKit SDK from a service. Port surface: `createRoom`, `issueSellerToken`,
`createIngress`, `startHlsEgress`, `stopHlsEgress`, `closeRoom`, `getSfuUrl`.

**Intentional exception — external calls inside `@Transactional` (`StartLiveService`).**
The root rule is "minimize external calls in a tx"; here it is deliberate. Order:
guard state (`canStartLive()` re-check) → validate pinned product → `issueSellerToken`
→ `startHlsEgress` → domain transition → persist. The media calls run **inside** the tx,
*after* re-validation, so we never hit the media server in a bad state, and the `egressId` is
committed together with the room (needed later by `stopHlsEgress`).
**Accepted risk:** the DB connection is held across media I/O. Keep this order —
**reviewers must NOT flag the in-transaction network call here.**
If the tx rolls back after `startHlsEgress`, an `afterCompletion` hook in `StartLiveService`
stops the egress (rules + intentional catch-log are commented inline — don't "fix" them).
A process crash still orphans the egress → needs a reconciliation batch, not built yet.

**Pinned product rule:** starting requires **exactly one** pinned product (`validatePinnedProduct`).

## RTMP Ingress — seller external-encoder (OBS) input

Besides WEBRTC (browser token publish), OBS / pro encoders push over **RTMP**. This is a **prep step
decoupled from scheduling**: the seller calls `POST /rooms/{roomId}/ingress` (`PrepareIngressService`)
to get an `rtmpUrl` + `streamKey` and configures the encoder — the stream type is **not** fixed at
reservation (a room defaults to `LiveStreamType.WebRtc`).

- **streamKey is a credential — never store or log it.** Only `ingressId` is persisted
  (`LiveStreamType.Rtmp` / `assignRtmpIngress`); the streamKey is returned **once** in the issue response
  (re-fetch via LiveKit `listIngress`). `IngressResult` / `IngressCredentialView` mask it in `toString()`.
- **Guards**: Scheduled only (`canPrepareIngress`) + ownership (`findByIdAndSellerId`). **Idempotent**:
  reject if already RTMP (`isRtmp`) — decided from the DB alone (no LiveKit re-fetch). Re-issue on a lost
  streamKey is a separate ticket.
- **The ingress is bound to the room by roomId** and LiveKit auto-creates that room when OBS connects, so
  it does not depend on reservation-time SFU room creation / `empty_timeout`.
- **Not done yet (follow-up tickets)**: the go-live transition for RTMP rooms (OBS connect =
  `ingress_started` webhook → Live) and the `issueSellerToken` skip branch, plus orphan-ingress
  reconciliation / `deleteIngress` — which also closes the concurrent double-`prepare` race (two
  simultaneous calls both pass the `isRtmp` guard → duplicate ingress → one orphan). For now calling
  `StartLive` on an RTMP room does not break — it just issues an unused seller token.

## LiveKit Webhooks — receiver + handler contract

LiveKit calls `POST /webhooks/livekit` (live-app) for events we can't learn from our own API —
`room_finished` (seller left without ending), `ingress_started`/`track_published` (RTMP actually
connected), `egress_ended`, etc. Auth is **body signature**, not Spring Security: the path is
`permitAll` and `WebhookVerifier` (← `LiveKitWebhookVerifier` wrapping SDK `WebhookReceiver`) verifies
the `Authorization` JWT. Body is read via **bounded streaming read** (not `@RequestBody byte[]`) so
chunked requests can't buffer unbounded (memory DoS); raw bytes go through as-is (UTF-8) for the signature.

`LiveWebhookService` verifies then dispatches to every `LiveWebhookHandler` whose `supports(type)` matches.

**Handlers are owned by each domain feature, not by the webhook package.** The receiver only provides the
`LiveWebhookHandler` port; RTMP transition / end-cleanup / orphan-ingress work each add its own
`@Component` handler (auto-registered into the injected `List`). A handler is a **thin trigger adapter** →
call the domain use-case; keep logic in the service. **Handlers MUST be idempotent** — LiveKit re-sends on
failure and replays are possible within the token TTL, so the same event may arrive more than once
(e.g. no-op if already ended, or consume-mark by event id). The service **isolates + logs** handler
exceptions and still returns 200 (a thrown exception does NOT trigger re-send), so loss-critical work must
guarantee itself via retry/reconciliation inside the handler.

## Persistence & Cache

- **Schema is `live_schema`** (DDL: `db/migration/live/`, Flyway-owned).
- **Entity is mutable, domain record is not.** `LiveRoomRepositoryImpl.save()` is an upsert by
  `id`: null → insert; non-null → load entity, `LiveRoomMapper.updateEntityFromDomain(...)`, save.
  Mutation lives in `LiveRoomEntity` (`updateXxx` / `applyXxx`), driven by `LiveRoomMapper`.
- **Conversion is in `LiveRoomMapper`** (MapStruct `@Mapper`, `componentModel="spring"` — injected, not static). Flat fields map automatically; the sealed `LiveStatus` ↔ `LiveRoomStatus` enum, variant-specific fields, and entity mutators are handled by `default` / `@AfterMapping` methods.
- **Ranking list reads cache only.** `GetLiveService` → `findTopByViewers(limit)` over Redis
  (`LiveRoomCache`), no DB hit; empty list when nothing is live. Keys in `LiveRedisKeys`
  (`live:room:{id}`, `live:ranking`) — package-private, keep them there.

## Room Token — chat (streaming-app) entry auth

`enter` issues an **RS256 room token** alongside the viewing URL; chat verifies it with the public key
as its entry gate. live **signs with a private key** (`live.room-token.private-key`, env-injected) —
distinct key, algorithm, and `aud` from the api-app auth token (HMAC, shared `JwtTokenProvider`), so the
two are never interchangeable.

- **Issued in** `EnterLiveService` — after authoritatively checking the room is live, signs via
  `RoomTokenIssuer` (← `RoomTokenProvider`). Members get identity-based tokens; unauthenticated viewers
  get an **ephemeral GUEST token** (fresh userId per entry).
- **Claims**: `iss=live`, `aud=chat`, `sub=userId`, `room`, `role` (USER→BUYER / SELLER→SELLER /
  unauth→GUEST), `owner` (`userId==sellerId` — chat's PII/notice gate), `nickname`/`email` for members
  only, `exp` (default 90s).
- **email is PII**: a JWT is signed, not encrypted → never log the raw token (see `EnterLiveView`,
  filter, principal comments). chat must **not pass it as a query param** (access-log leakage) — use a
  subprotocol / first frame instead.
- **Not single-use**: short TTL only, no jti → replayable within the TTL window. True one-time use needs
  jti + a SETNX consume-mark on the chat side (out of scope).
- **live-app principal**: the filter carries `LiveUserPrincipal` (userId·role·nickname·email); its
  `getName()=userId` keeps the shared `CurrentUserIdArgumentResolver` working (backward compatible).
- **Undecided**: `ADMIN` role source (api-app has only USER/SELLER) — not issued until agreed. Public
  key is a single static key (v1); promote to `kid`+JWKS if rotation is needed.

## Errors

Throw the domain exceptions (`LiveNotFoundException`, `InvalidLiveStateException`,
`LiveMediaException`) — each maps to a `LiveErrorCode` (`LIVE-001` media, `LIVE-002` not-found,
`LIVE-003` invalid-state). Don't throw raw `IllegalState`/`RuntimeException` from services.

## Tests

`live-core/src/test` — service tests (Mockito mocks on ports), media adapter, and redis repo.
Build fixtures with **FixtureMonkey**. Run: `./gradlew :modules:live:live-core:test`.
