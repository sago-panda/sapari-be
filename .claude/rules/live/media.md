---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{LiveKitMediaManager,HlsRendition}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/metrics/MeteredLiveMediaManager.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveKitConfig.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/LiveMediaManager.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{StartLive,EndLive,GoLiveByRtmp,PrepareIngress,ReissueIngress}Service.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{PostCommitMediaCleanup,EgressRollbackCompensation,RtmpIngressAssigner,IngressStartedWebhookHandler}.java"
  - "apps/live-app/src/main/resources/application*.yaml*"
  - "apps/live-app/src/main/java/com/sapari/liveapp/security/{CredentialIssue*,IngressReissueRateLimitProperties}.java"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{LiveKitMediaManager,MeteredLiveMediaManager,StartLiveService,EndLiveService,GoLiveByRtmpService,PrepareIngressService,ReissueIngressService}*Test.java"
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

Stream type is fixed at creation (`CreateLiveService`); an RTMP room cannot start without a key (LIVE-017, `StartLiveService`) and a WebRTC room gets no key (LIVE-011, `PrepareIngressService`). **Go-live has two independently-ordered steps**:
seller `arm` → `Ready`, and OBS connect → `ingress_started` webhook. Whichever lands second triggers `Live`,
so both sides must reach the same outcome — if they diverge, the room's fate depends on arrival order.

- **Promotion requires the room to acknowledge the ingress** (`LiveRoom.hasIngress`) — webhook, batch and
  seller-start all match. A room can hold two live ingresses (a race loser whose delete failed); promoting on
  an unacknowledged one starts a broadcast the orphan-media job then cuts.
- `createIngress` runs outside any transaction, so `PrepareIngressService`'s and `ReissueIngressService`'s guards
  are snapshot reads and assignment and reissue are conditional UPDATEs (`RtmpIngressAssigner`). **The one place
  where a WHERE clause is the domain guard** — add a `LiveStatus` variant and the compiler won't remind you. Don't spread it.
- Accepted: reissue shares Prepare's orphan-grace race — the grace keys on the room's `updated_at`, so the orphan job can
  delete a key created moments before its CAS. Reissuing recovers only while `Scheduled`; once started, the room sits in
  `Ready` for good (the expire batch skips it as misconfigured; root fix is a follow-up). Reissue confirms the old key
  is gone rather than leaving it to that job (`ReissueIngressService`).
- Accepted: no backfill (SPR-171) — a room scheduled before the change stays WebRTC and cannot switch to OBS, and a
  room created with the wrong type cannot switch back; the only way out is cancel and recreate (`PrepareIngressService`,
  `ReissueIngressService`).
- End cleanup deletes ingress room-wide **before** `closeRoom`; a survivor lets OBS re-create the room.
- **Reissue is rate-limited because one request is up to five LiveKit calls** (`createIngress` 1 +
  `deleteIngress`/`listRoomIngress` 2 each) and `Scheduled` put no count on it — a single parked room could
  issue unbounded LiveKit calls, and LiveKit slowing down slows **start and end** through the same manager.
  The cap does not stand in for the CAS: exclusivity is `RtmpIngressAssigner.reassignIfCurrent`, this is a call
  ceiling. A 429 does **not** block broadcasting — the old key stays valid — which is why it sits lower than the
  thumbnail cap (`IngressReissueRateLimitProperties`). First issue is deliberately outside it: `assignRtmpIngressIfAbsent`
  bounds it to once per room (`CredentialIssuePaths`).
