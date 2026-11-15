---
paths:
  - "apps/live-app/src/main/java/com/sapari/liveapp/scheduler/**"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{ReconcileLockConfig,SchedulingConfig,ShedLockTableGuard,TrackingLockProvider}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{Reconcile*,EndStaleLiveService,ExpireOrphanLiveService,PostCommitMediaCleanup}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{*ReconcilePolicy,Reconcile*}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/LiveReconcile*.java"
  - "db/migration/live/*.sql"
  - "apps/live-app/src/main/resources/application*.yaml*"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{Reconcile,EndStaleLive,ExpireOrphanLive,ShedLock}*Test.java"
---

# live — orphan reconciliation (detail)

Split from `modules/live/AGENTS.md` §Orphan reconciliation (SPR-182); its invariants moved here too (SPR-154,
section below). Nothing here repeats a code comment.

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
`STALE_LIVE_CANDIDATE` / `STALE_LIVE_BATCH_SATURATED` semantics → `ReconcileAction` · replay recovery runs
after the stale-live step in its own `try`, under the same lock → `EndStaleLiveScheduler`, `RecoverStuckReplaysService`.

**Not in any comment:**

- **Delete the removed `batch-size` keys from every deployed yaml/env before upgrading** — why:
  `LiveReconcileConfig`.
- **Blast radius is bounded by the clock alone** (accepted): a bad bulk UPDATE that marks many rooms `Ended`
  gets one orphan-media round budget of unrestricted deletion — a count cap could not tell a real backlog
  from a bad UPDATE either. The compensating alert on `live.reconcile.acted{job,action}` (`SFU_ROOM_CLOSE_REQUESTED` /
  `INGRESS_DELETE_REQUESTED`) is **not registered**; this repo holds no alert rules. Until it is, the only
  control is the bulk-UPDATE procedure in `modules/live/AGENTS.md` §Design-time invariants.

## Orphan reconciliation — three `@Scheduled` jobs (live-app)

Triggers in `liveapp/scheduler` are thin; policy and loops live in `live-core`. Two of them move broadcasts:
`expire-ready` can **start** one, `end-stale-live` can **end** one that is still on air.
Config keys, ShedLock lease and round budget, boot guards, removed keys and the bulk-UPDATE procedure →
`.claude/rules/live/reconcile.md`. The scheduler pool is sized one thread per job, list refresh included (`SchedulingConfig`).

- Judge **per room, right before touching it** — a once-per-round snapshot expires rooms that reconnected.
- Expire-ready has **three** outcomes: promote / expire / **leave alone** (publishing, but not this room's).
- **A global sweep returning zero is not evidence.** `end-stale-live` aborts the round when the whole cluster
  reports no active egress. Cost: an idle cluster never gets swept — accepted, the other direction cuts live
  broadcasts. Don't drop the guard; verify per room instead.
- If the global snapshot marks a candidate active but its per-room list is empty, skip it for this round and
  record `SKIPPED_EGRESS_SNAPSHOT_MISMATCH`; partial routing/permission failures must not end a broadcast.
- **`BUFFERING` counts as publishing** — that is a reconnecting OBS.
- **A publishing ingress is spared only while the room acknowledges it** — unacknowledged ones are reclaimed
  mid-publish. Waiting for `Ended` instead would deadlock: only this job can get the room there.
- **The seller's LiveKit token (6h, unrevocable) can recreate a closed room.** It has no ingress and no egress,
  so only `listAllRooms` sees it. Judge on **DB status only** — gating on participant count spares exactly the
  case this targets. A shorter TTL is not a substitute; it needs a refresh endpoint first.
- **Never judge staleness by viewer count** — HLS viewers are not SFU participants. `started_at`, not `updated_at`.
- **When in doubt, don't delete.** No DB row → log only; grace covers the create-then-save window.
- **Don't change the ShedLock table's time columns to `timestamptz`** — why: `V202609051200__shedlock.sql`.
- **Terminal (`Ended`/`Cancelled`) rooms get no grace — neither the room sweep nor ingress deletion** — why:
  `ReconcileOrphanMediaService`.
