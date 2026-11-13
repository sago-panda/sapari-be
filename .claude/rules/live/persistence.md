---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/persistence/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/redis/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/recentview/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/RecentView*.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{LiveViewerCounter,RecentViewRecorder}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{LiveListCacheSync,RefreshLiveListService,GetLiveService,GetUpcomingLivesService,GetRecentLivesService,RecentViewWriter}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/adapter/**"
  - "apps/live-app/src/main/java/com/sapari/liveapp/scheduler/LiveListRefreshScheduler.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/LiveListSchedulingConfig.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/RecentLiveController.java"
  - "db/migration/live/*.sql"
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/repository/**/*"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{LiveRoomRepository,LiveRoomCache,LiveListCacheSync,RefreshLiveListService,RecentView,GetUpcomingLivesService,GetRecentLivesService,ChatLiveViewerCounter}*Test.java"
---

# live — persistence, lists & recent views

Moved from `modules/live/AGENTS.md` §Persistence & cache (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

## Persistence & cache

- Schema `live_schema`, Flyway-owned (`db/migration/live/`). Entity mutable, record immutable.
- **Every state-transition read takes a row lock** (`findByIdForUpdate`, `findByIdAndSellerIdForUpdate`);
  unlocked reintroduces the double-`startHlsEgress` race. Ownership stays **in the query** — a service-side
  check would leak room existence. Hibernate emits **`for no key update`**, not `for update`; don't grep for
  the latter and conclude the lock is missing.
- `status`/`streamInfo`/`scheduledAt` are mapped by hand, not auto-mapped — why: `LiveRoomMapper`.
- **Live list = Redis, DB is the source.** `GET /rooms` reads Redis only (`LiveRoomCache`). Every transition into
  `Live` registers and every exit removes via `LiveListCacheSync`; a new exit (e.g. into `Suspended`)
  is covered by §Design-time invariants. Store failures are swallowed + counted on purpose; `RefreshLiveListService` (15s, no ShedLock —
  idempotent) rewrites the list from the DB. Accepted: a round racing a transition costs one round either way.
- **Upcoming list** (`GET /upcoming`) is DB, `status IN (SCHEDULED, READY)` — inclusion only, so new states stay
  out. Cursor key precision → `GetUpcomingLivesService`.
- **Recent views** (`GET /recent`, SPR-172): only a successful **member** entry is recorded, after the token is
  issued, and recording never fails or delays `enter` — drops are counted (`RecentViewRecorder`, `RecentViewConfig`).
  One row per (user, room), listed for 90 days.
  ⚠️ **Retention unresolved (follow-up ticket)**: viewing history is per-member behaviour data, yet neither
  >90-day rows nor withdrawn members' rows are ever deleted.
