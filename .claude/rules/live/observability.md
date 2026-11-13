---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/metrics/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{LiveMetrics,PromotionTrigger,ReconcileAction,ReconcileAbortReason,ReconcileLockResult,ReconcileJob}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveMetricsConfig.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{LiveSecurityConfig,TrackingLockProvider}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/scheduler/**"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{MicrometerLiveMetrics,PrometheusScrape,ActiveRoomGauge,LiveMetrics}*Test.java"
---

# live — observability `LiveMetrics` (detail)

Split from `modules/live/AGENTS.md` §Observability (SPR-182); its rules moved here too (SPR-154, section below),
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
caught → `MeteredLiveMediaManager.timed`, `ReconcileLockConfig`. ·
`live.list.*` (cache write, viewer count, saturated, refresh failed) as the only signal of a swallowed
post-commit write → `LiveMetrics`, `LiveListCacheSync`, `RefreshLiveListService` · `live.recent_view.dropped{reason}`
→ `RecentViewRecorder`, `RecentViewConfig`.

## Observability — `LiveMetrics`

Ports-and-adapters, same as media: `application/port/LiveMetrics` (no micrometer — ArchUnit rule 16) ←
`infrastructure/metrics`. Metric semantics → `.claude/rules/live/observability.md`. Rules (each class's
comment says why):

- `LiveMetrics.NOOP` via `ObjectProvider`, **never `@ConditionalOnBean`** (`LiveMetricsConfig`).
- `MeteredLiveMediaManager` (`@Primary`) **must never swallow** — the port failure directions above are deliberate.
- **The metrics adapter never throws** (`MicrometerLiveMetrics.safe`, `MeteredLiveMediaManager.record`).
- **`management.server.port` must differ from the app port and stay cluster-internal**; the actuator chain is
  open only because of that (`managementPortMustDiffer`, `LiveSecurityConfig`). Never relax one without the other.
