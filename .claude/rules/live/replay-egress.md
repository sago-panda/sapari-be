---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{LiveKitMediaManager,HlsRendition,MasterPlaylistGenerator}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{HlsEgressResult,MasterPlaylistPublisher,LiveMediaManager}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{GetLiveReplayService,StartLiveService,GoLiveByRtmpService,EndLiveService}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/persistence/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/model/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{EgressWebhookHandler,UpdateVodStatusService,RecoverStuckReplaysService,GetReplaysService}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/ReplayListController.java"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{GetLiveReplayService,MasterPlaylist,LiveRoomMapper,EgressWebhookHandler,UpdateVodStatusService,RecoverStuckReplaysService,GetReplaysService,ReplayListController,LiveRoomVodStatusUpdate,LiveRoomReplayQueries}*Test.java"
---

# live — HLS egress artifacts & replay (detail)

Split from `modules/live/AGENTS.md` §Media ports (SPR-182). Checked by `LIVE-08`. Nothing here repeats a
code comment.

**Lives in code, not here:** names must differ (the exact `invalid_argument`), `index.m3u8` sliding window
vs `playlist.m3u8` EVENT, archive path `HlsEgressResult` → `StreamInfo` → `endLive` → `LiveKitMediaManager`
(`startRenditionEgress`) · `variantPlaylistPath()` stays on the live playlist → `HlsRendition` · no publisher
bean, 720p default → `MasterPlaylistPublisher` · 404 `LIVE-007` for non-Ended / no archive →
`GetLiveReplayUseCase` · raw value kept for transitions, legacy copies read as no archive, Ended re-save
preserves them, no filename allowlist → `LiveRoomMapper` · publisher 15s total timeout incl. retries →
`MasterPlaylistPublisher` · `archive-master.m3u8`, both-or-neither master publish, up to 30s →
`LiveKitMediaManager.publishMaster`.
SPR-159: egress status → `vod_status` mapping, 720p-representative-only matching, `egress_started` race →
`EgressWebhookHandler` · monotonic WHERE guard, `updated_at` left alone → `LiveRoomJpaRepository`,
`UpdateVodStatusService` · why the vod columns are read-only in the entity → `LiveRoomEntity` · recovery grace and
its accepted inaccuracy → `RecoverStuckReplaysService` · deploy-time backfill → `V202611121201` header.

## Replay status (`vod_status`, SPR-159)

- **Replay (single and `GET /api/v1/lives/replays`) needs `vod_status = READY`** besides Ended ∧ public ∧ archive;
  the list carries no playback URL (`GetReplaysService`, `GetLiveReplayService`).
- **`vod_status` follows the egress, not the room** — a recording that ends before the room is still a replay.
- **Two writers only, both conditional UPDATEs that move only from NONE/PROCESSING**: the webhook's
  `advanceVodStatus` and the recovery's `recoverStuckReplays`. **Never write the vod columns through the entity.**
- **FAILED is terminal** — a partial playlist may be playable yet stays hidden; recovery is manual SQL.
- **Accepted: a lost egress webhook is repaired from the DB, not LiveKit** (`RecoverStuckReplaysService`), so a failed
  or unfinished recording whose files remain also becomes READY. It rides the end-stale-live job: disabling that
  job stops it (`EndStaleLiveScheduler`).
