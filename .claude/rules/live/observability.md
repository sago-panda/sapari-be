---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/metrics/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{LiveMetrics,PromotionTrigger,ReconcileAction,ReconcileAbortReason,ReconcileLockResult,ReconcileJob}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveMetricsConfig.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{LiveSecurityConfig,TrackingLockProvider}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/scheduler/**"
---

# live — observability `LiveMetrics` (detail)

Split from `modules/live/AGENTS.md` §Observability (SPR-182). The rules there are not repeated here, and
nothing here repeats a code comment — where the reasoning already sits next to the code, this file only
names the class.

**Reasoning that lives in code comments** (read the class, not this file):
transitions/promotions counted after commit → `MicrometerLiveMetrics`, `LiveMetrics` ·
round counters fire on a 0-candidate round → `ReconcileStaleLiveService`, `ReconcileExpiredReadyService` ·
`reconcileRoundAborted` is round-scoped → `ReconcileAbortReason`, `LiveMetrics` ·
`SKIPPED_EGRESS_LOOKUP_FAILED` / `SKIPPED_INGRESS_MISSING` → `ReconcileAction` ·
orphan-media has no abort guard and owns `live.livekit.egress.rooms` (rooms, not records) → `ReconcileOrphanMediaService` ·
three `PromotionTrigger` values → `PromotionTrigger` · `live.room.active` read beside the egress gauge → `ActiveRoomGauge` ·
lock result tags, `finally` unlock, no release on shutdown (lease expiry hands over) → `ReconcileLockConfig`,
`TrackingLockProvider`, `SchedulingConfig` · normal-return flag + `failed` counted from `finally`, nothing
caught → `MeteredLiveMediaManager.timed`, `ReconcileLockConfig`.
