---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/model/{LiveRoom,LiveStatus,LiveStreamType}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{CreateLive,StartLive,EndLive,CancelLive,UpdateLive,ExpireOrphanLive,EndStaleLive,GoLiveByRtmp,EnterLive,GetSellerRooms}Service.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/LiveRoomTabs.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/persistence/**"
  - "db/migration/live/*.sql"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{GetLiveRoom,GetLiveRoomDetail}Service.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/{LiveController,LiveRoomManageController}.java"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{LiveRoom,CancelLiveService,UpdateLiveService,CreateLiveService,GetSellerRoomsService}*Test.java"
---

# live — state machine

Moved from `modules/live/AGENTS.md` §State machine (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

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

- `expire()` and `cancel()` publish no `RoomEnded` — the room was never `Live`, so chat has no session.
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
