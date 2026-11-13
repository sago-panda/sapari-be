---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{LiveKitMediaManager,HlsRendition,MasterPlaylistGenerator}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{HlsEgressResult,MasterPlaylistPublisher,LiveMediaManager}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{GetLiveReplayService,StartLiveService,GoLiveByRtmpService,EndLiveService}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/persistence/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/model/**"
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
