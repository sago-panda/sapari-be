---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{LiveKitMediaManager,HlsRendition}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/metrics/MeteredLiveMediaManager.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveKitConfig.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/LiveMediaManager.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{StartLive,EndLive,GoLiveByRtmp,PrepareIngress}Service.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{PostCommitMediaCleanup,EgressRollbackCompensation,RtmpIngressAssigner,IngressStartedWebhookHandler}.java"
  - "apps/live-app/src/main/resources/application*.yaml*"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{LiveKitMediaManager,MeteredLiveMediaManager,StartLiveService,EndLiveService,GoLiveByRtmpService,PrepareIngressService}*Test.java"
---

# live — media ports & RTMP

Moved from `modules/live/AGENTS.md` §Media ports, §RTMP (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

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

- **Promotion requires the room to acknowledge the ingress** (`LiveRoom.hasIngress`) — webhook, batch and
  seller-start all match. A room can hold two live ingresses (a race loser whose delete failed); promoting on
  an unacknowledged one starts a broadcast the orphan-media job then cuts.
- `createIngress` runs outside any transaction, so `PrepareIngressService`'s guards are snapshot reads and
  assignment is a conditional UPDATE (`assignRtmpIngressIfAbsent`). **The one place where a WHERE clause is
  the domain guard** — add a `LiveStatus` variant and the compiler won't remind you. Don't spread it.
- End cleanup deletes ingress room-wide **before** `closeRoom`; a survivor lets OBS re-create the room.
