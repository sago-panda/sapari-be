---
paths:
  - "modules/live/live-api/src/main/java/**/*"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{LiveEventPublisher,RoomEndedSignal}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/redis/{RedisLiveEventPublisher,LiveRedisKeys}.java"
  - "modules/chat/chat-core/src/main/java/com/sapari/chat/application/handler/LiveRoomEndedHandler.java"
  - "modules/chat/chat-core/src/main/java/com/sapari/chat/application/port/LiveRoomEndedSource.java"
  - "modules/chat/chat-core/src/main/java/com/sapari/chat/infrastructure/redis/{RedisLiveRoomEndedSource,ChatRoomEndedRedisRepository}.java"
  - "modules/chat/chat-core/src/main/java/com/sapari/chat/application/service/{ClearRoomEndedMarker,GetChatHistory,KickUser,ReconcileKickList}Service.java"
  - "apps/streaming-app/src/main/java/**/ChatSessionRegistry.java"
---

# live — contracts other domains consume

Added in SPR-154 so these load when the work starts on either side of the boundary. What and why only; each
fact's home is named, not repeated.

- **`live-api` is the only surface other domains may use** (root `AGENTS.md` dependency rules). chat-core reads
  it today (kick, kick reconcile, chat history, ended-marker clear) — **changing a port or view is a
  cross-domain change: check those callers in the same MR.**
- **`GetLiveRoomUseCase.live` is false for anything not `Live`, `Suspended` included**; the consuming domain
  decides what to reject → state-machine rules §State machine.
- **Room end reaches chat two ways — the ended key and the `RoomEnded` event — both at-most-once**; chat reads
  both, and its TTL lower bound is hand-copied, so change both sides together → state-machine rules,
  `RedisLiveEventPublisher`/`LiveRedisKeys` ↔ `RedisLiveRoomEndedSource`/`LiveRoomEndedHandler`.
- **Room-token claims and the `exp` cap are read by streaming-app** (`ChatSessionRegistry`); changing a claim or
  the cap is a cross-domain change → enter rules §Room token, `modules/live/AGENTS.md` §Design-time invariants.
