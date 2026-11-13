---
paths:
  - "apps/live-app/src/main/java/com/sapari/liveapp/scheduler/**"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{ReconcileLockConfig,SchedulingConfig,ShedLockTableGuard,TrackingLockProvider}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{Reconcile*,EndStaleLiveService,ExpireOrphanLiveService,PostCommitMediaCleanup}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{*ReconcilePolicy,Reconcile*}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveReconcile*.java"
  - "db/migration/live/*.sql"
  - "apps/live-app/src/main/resources/application*.yaml*"
---

# live — orphan reconciliation (detail)

Split from `modules/live/AGENTS.md` §Orphan reconciliation (SPR-182). The invariants stay there; nothing
here repeats them or a code comment.

**Lives in code, not here:** config keys and defaults (thresholds, cron stagger, batch sizes, leases) →
`LiveReconcileProperties`, `SchedulingConfig`, `apps/live-app/src/main/resources/application.yaml.example` ·
what each job selects, decides and acts via → the three `Reconcile*Service` Javadocs · ShedLock per job,
boot guards (`ShedLockTableGuard`, `leasesMustOutlastTheirCronPeriod`, `lockIntervalsMustFitInCron`),
lease = takeover delay, why only orphan-media strictly needs the lock → `ReconcileLockConfig` ·
clock-bounded rounds (`roundBudget` = 4/5 of the lease, `ROUND_BUDGET_EXHAUSTED`, single lease default) →
`LiveReconcileProperties`, `ReconcileAction` · orphan-media iterates by room, no count cap, no rotation,
`ORPHAN_ROOM_CANDIDATE` as the signal, no lease extension → `ReconcileOrphanMediaService`, `OrphanMediaScheduler` ·
removed `batch-size` keys refuse to boot → `LiveReconcileConfig` · deadline checked only between candidates,
so one room can outlive its lease (duplicate cleanup accepted) → `LiveReconcileProperties.roundBudget` ·
`STALE_LIVE_CANDIDATE` / `STALE_LIVE_BATCH_SATURATED` semantics → `ReconcileAction`.

**Not in any comment:**

- **Delete the removed `batch-size` keys from every deployed yaml/env before upgrading** — why:
  `LiveReconcileConfig`.
- **Blast radius is bounded by the clock alone** (accepted): a bad bulk UPDATE that marks many rooms `Ended`
  gets one orphan-media round budget of unrestricted deletion — a count cap could not tell a real backlog
  from a bad UPDATE either. The compensating alert on `live.reconcile.acted{job,action}` (`SFU_ROOM_CLOSE_REQUESTED` /
  `INGRESS_DELETE_REQUESTED`) is **not registered**; this repo holds no alert rules. Until it is, the only
  control is the bulk-UPDATE procedure in `modules/live/AGENTS.md` §Design-time invariants.
