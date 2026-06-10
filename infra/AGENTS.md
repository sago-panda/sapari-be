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

How it runs:
- **local:** `DB_URL=… DB_USER=… DB_PASSWORD=… ./infra/migration/migrate.sh` (needs Flyway CLI;
  auto-detects `db/migration` from repo root).
- **docker:** image built from `infra/migration/Dockerfile`.
- **prod:** same image run by a Helm pre-upgrade Job (wired when prod is promoted).

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
