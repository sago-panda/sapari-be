# infra — runtime topology & deploy

Infra/deploy context for sapari-be. Loaded only when working on infra, deploy, or CI/CD.
For application code conventions, see the root `AGENTS.md`.

## Runtime Topology

- Each `apps/*` deploys as a separate Pod on k3s.
- **Sync calls**: in-cluster REST via Service DNS. Cross-domain calls go through `*-api` interfaces.
- **Async messaging**: Redis Pub/Sub (broadcast start/end fan-out, etc.).
- **Shared state**: PostgreSQL · Redis · OpenSearch · S3 — all Pods point to the same instances.
- **CI**: GitLab CI · **CD**: ArgoCD (auto-sync on dev, manual approval on main).

## Migrations (Flyway, schema-per-domain)

DDL is owned by **Flyway**, not Hibernate (`ddl-auto` never creates). Runner lives **here**:
`infra/migration/` (`migrate.sh` + `Dockerfile`); SQL lives at repo root `db/migration/<domain>/`.

- **schema = domain = owns its migrations.** Each domain gets its own PostgreSQL schema
  (`<domain>_schema`) with an independent `flyway_schema_history` and version sequence.
- **No cross-schema FK** — domains reference each other by `uuid` id columns only, so schemas
  have no apply-order dependency (the runner loops schemas in any order).
- **Run on PRIMARY only** (replicas get it via WAL). `outOfOrder=true`, so timestamp versions
  applied out of branch-merge order still take.
- **Version naming: `V<yyyyMMddHHmm>__description.sql`** (current `V1__init_*` are the seed DDL).
- **Adding a schema:** one `folder:schema` line in `migrate.sh`'s `SCHEMAS` list **+** create
  `db/migration/<folder>/`. Nothing else.

- **Deployment prerequisite — the migration leads, live-app follows.** Since SPR-142 the reconcile
  schedulers take a ShedLock lock on `live_schema.shedlock`, created by `db/migration/live/V*__shedlock.sql`.
  Roll out the migration **before** the app. `ShedLockTableGuard` **refuses to boot** unless it can actually
  *write* to the table (an upsert on a reserved row, not an existence check — a wrong column shape or a
  role without `INSERT`/`UPDATE` passes existence and then fails every round), so the failure is loud and
  at the right moment — same choice as `managementPortMustDiffer`.
  Without that guard the app would come up fine and only the three cleanup jobs would stop: every round
  throws, but from the ShedLock proxy **outside** each scheduler's `try/catch`, so the prepared domain
  message never appears and still-billing egress goes unreclaimed. The guard adds no new boot-time DB
  dependency (`ddl-auto: validate` already needs the DB); it covers what `validate` cannot, since the
  lock table has no entity. **This reaches local development too**: the `@Tag("context")` tests boot a real
  context, so a developer whose local database predates this migration now fails at `contextLoads` — run
  `migrate.sh` against the local DB, or set `live.reconcile.enabled=false` locally.

How it runs:
- **local:** `DB_URL=… DB_USER=… DB_PASSWORD=… ./infra/migration/migrate.sh` (needs Flyway CLI;
  auto-detects `db/migration` from repo root).
- **docker:** image built from `infra/migration/Dockerfile`.
- **prod:** same image run by a Helm pre-upgrade Job (wired when prod is promoted).

## Observability ports (live-app)

- **live-app listens on two ports: `server.port` (user traffic) and `management.server.port` (metrics).**
  The metrics port serves `/actuator/health` and `/actuator/prometheus` with **no authentication** — a
  Prometheus scraper has no way to hold a JWT, so the gate is the network, not the app.
- **The management port binds to every interface** — `management.server.address` is deliberately unset,
  because pinning it to `127.0.0.1` would also block the scraper (Prometheus reaches the pod by pod IP,
  not loopback). So anything that can route to the pod on that port reads the metrics unauthenticated.
- **Therefore the management port must never be exposed outside the cluster**: keep it on a ClusterIP
  Service, do not put it behind the ingress, and restrict it to the monitoring namespace with a
  NetworkPolicy where the cluster supports one. `/actuator/prometheus` carries business volume
  (broadcasts running, seller activity) and its gauge queries the DB per scrape.
- **Probes must point at `/actuator/health/liveness` and `/actuator/health/readiness`, not at
  `/actuator/health`.** Measured on this branch: with the DB stopped, `/actuator/health` answers **503**
  while both probe groups stay **200**. A liveness probe on the aggregate path therefore turns a database
  blip into a cluster-wide restart loop — every pod is killed while the DB is down, which is exactly when
  restarting helps least. **Measured on Boot 4.0.6 with no `probes.enabled` and no Kubernetes present**:
  `/actuator/health` reported `groups:["liveness","readiness"]` and both sub-paths answered 200, so the
  groups are there without extra config on this version — but the load-bearing part is the manifest
  choosing the right path, not the app.
- The app refuses to start when the two ports are equal (`LiveSecurityConfig.managementPortMustDiffer`),
  so a config slip fails loudly instead of quietly publishing metrics on the user port. Manifests still
  have to *set* the management port — nothing here defaults it.
- **Deployment prerequisite — read before merging live-app.** `MANAGEMENT_PORT` (or
  `management.server.port`) must exist in the environment *before* this app is rolled out: without it the
  app refuses to start by design. That is fail-closed on purpose — booting without it would put metrics on
  the user port — but it means the manifest change leads, and the app follows. Developers running locally
  need the same key; copy the `management` block from `application.yaml.example`.
- **Not done yet in this repo**: the manifests themselves live in the infra repo, so none of the above is
  enforced from here. Until they exist, metrics are only as private as the cluster's default networking,
  and the `live.room.active` gauge's 5-second cache is the only thing bounding DB load from scrapes.

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
