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
`startHlsEgress`, `stopHlsEgress`, `closeRoom`, `getSfuUrl`.

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

## Persistence & Cache

- **Entity is mutable, domain record is not.** `LiveRoomRepositoryImpl.save()` is an upsert by
  `id`: null → insert; non-null → load entity, `LiveRoomMapper.updateEntityFromDomain(...)`, save.
  Mutation lives in `LiveRoomEntity` (`updateXxx` / `applyXxx`), driven by `LiveRoomMapper`.
- **Conversion is in `LiveRoomMapper` (static)** — sealed `LiveStatus` ↔ `LiveRoomStatus` enum.
- **Ranking list reads cache only.** `GetLiveService` → `findTopByViewers(limit)` over Redis
  (`LiveRoomCache`), no DB hit; empty list when nothing is live. Keys in `LiveRedisKeys`
  (`live:room:{id}`, `live:ranking`) — package-private, keep them there.

## Errors

Throw the domain exceptions (`LiveNotFoundException`, `InvalidLiveStateException`,
`LiveMediaException`) — each maps to a `LiveErrorCode` (`LIVE-001` media, `LIVE-002` not-found,
`LIVE-003` invalid-state). Don't throw raw `IllegalState`/`RuntimeException` from services.

## Tests

`live-core/src/test` — service tests (Mockito mocks on ports), media adapter, and redis repo.
Build fixtures with **FixtureMonkey**. Run: `./gradlew :modules:live:live-core:test`.
