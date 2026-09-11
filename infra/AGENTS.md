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

## LiveKit media workers (measured, SPR-147)

Against `livekit-server` 1.8 + `egress` 1.9 + `ingress` 1.4 (`docker-compose.local.example.yml`).
**Each claim below is tagged**: *(measured)* was reproduced locally in SPR-147; *(docs)* comes from
LiveKit's documentation and config sample and has **not** been verified here.

- *(docs)* **Signalling (7880) may sit behind a load balancer** — official guidance, not just tolerated:
  *"any client could connect to any backend instance, regardless of the room they are in."*
  A participant that lands on the wrong node is **not** redirected; that node becomes a signalling
  bridge and proxies to the node hosting the room. **Redis is what makes this work** — without it
  LiveKit is not distributed at all.
- *(docs)* **TCP 7881 must NOT be behind a LB or TLS** — it has to be exposed on the node itself.
- *(docs)* **UDP: prefer the port range (50000-60000) over the 7882 single-port mux.** The range is
  LiveKit's own default and its config sample recommends *"a range of ports greater or equal to
  the number of vCPUs"*; each participant uses two ports. The two settings are mutually exclusive.
  → **The LiveKit node's security group can drop the 7882/udp mux rule and keep only the range** —
  **in that order: open the range first, then drop the mux rule.** Dropping it while only the mux is
  open fails every WebRTC connection (the local compose keeps the mux and notes the same constraint).
  The rule itself lives in the infra repo, not here — see *Not done yet in this repo*. The mux's
  concurrent-participant ceiling is not documented anywhere — don't quote a number for it.
- *(docs)* k8s constrains this further: host networking is required, so **one LiveKit pod per node**, and
  private/serverless clusters are unsupported (extra NAT layers break WebRTC).

### Egress credentials — D7 result *(measured)*: the worker DOES use the AWS default chain

Omitting `access_key`/`secret` from the egress request body **works**: upload succeeded in 12s with
credentials supplied only through the worker's environment. Negative control confirms it is really
the chain and not an open bucket — with the env vars removed the same request fails with
`no EC2 IMDS role found ... ec2imds: GetMetadata`.

- **IRSA on EKS is therefore viable**, but this is a *local* proof (environment variables). The
  instance-profile/IRSA link is still unverified on real EKS.
- ⚠️ When reproducing on EC2, set `http_put_response_hop_limit = 2` on the instance metadata
  options. Without it the chain fails inside the container and you conclude "IRSA impossible" — wrongly.
- The app still sends static keys (`LiveKitProperties.S3` keeps both fields required). Making them
  optional needs an explicit credential-mode setting first — blank keys must not be able to mean
  "chain mode" by accident, because the app boots fine and the broadcast goes `Live` while only the
  upload fails asynchronously; the only recovery is `end-stale-live` at its 60m threshold.

### /dev/shm is NOT the resource to size for egress *(measured)*

The widely-cited "container `/dev/shm` defaults to 64MB and Chrome dies" **does not reproduce here**.
`egress` 1.9 launches Chrome with `disable-dev-shm-usage`, so at `shm_size: 64mb` with 8 publishers
at 720p it ran 6 minutes with **zero bytes** used in `/dev/shm` (19 Chrome processes live).

→ In k8s, **do not** reach for `emptyDir{medium: Memory}` for this. Chrome puts its shared memory on
the container filesystem (`/tmp`), so the limit that actually matters is **`ephemeral-storage`**.
Keep a generous `shm_size`/emptyDir only as insurance against LiveKit dropping that flag.

### HLS playlists: `playlist.m3u8` is the archive, not a by-product *(measured)*

An egress writes two manifests per rendition and they are **not** interchangeable. Measured on a
220-segment broadcast that ended cleanly (`EGRESS_COMPLETE`):

| key | after the broadcast ends | role |
|---|---|---|
| `{rendition}/index.m3u8` | references the **last 5** segments | live sliding window |
| `{rendition}/playlist.m3u8` | `EXT-X-PLAYLIST-TYPE:EVENT`, references **all 220** | the archive / VOD |

- **Any S3 lifecycle rule must keep `playlist.m3u8` for as long as the segments it lists.** Expiring it
  early leaves the segments orphaned and makes replay impossible — there is no rule in this repo yet,
  so whoever writes it needs this constraint.
- Known gap (**SPR-148**): `LiveRoom.endLive` stores `streamInfo.hlsUrl()` into `hlsArchiveUrl`, and that URL is the
  *live* manifest, so the replay column points at a ~10-second window. **Both modes are affected** —
  with ABR off it is the 720p `index.m3u8`, and with ABR on it is `master.m3u8`, whose variants come
  from `MasterPlaylistGenerator` → `HlsRendition.variantPlaylistPath()`, i.e. the live manifests again.
  A fix therefore needs an archive URL carried out of `startHlsEgress` **and**, for ABR, a second
  master listing the `playlist.m3u8` variants. The segments are already in S3, so this is recoverable
  later **as long as `playlist.m3u8` outlives them**.
- SPR-148 owns the exposure side too, because both pull on the same URL in opposite directions: the
  archive is unreadable by the legitimate viewer, yet would be readable by anyone holding a playback
  URL once a CDN fronts the bucket (`index.m3u8` → `playlist.m3u8` is a one-word edit). Serving it
  from the same unsigned prefix fixes the first and worsens the second, so the archive moves to its
  own `archive/` prefix behind a permission check + presigned URL. **Measured 2026-09-11: the bucket
  is fully locked (all four public-access blocks on, no bucket policy, direct HTTP 403) and no
  CloudFront distribution exists yet** — not a live hole. **SPR-148 must land before a CDN fronts this
  bucket**: once a playback URL is reachable, moving the archive is a migration instead of a choice,
  and any URL already handed out keeps working against the old prefix.

### Timing budget *(measured)*

`egress start` → first segment in S3: **12-15s** (720p single rendition, 2s segments). Useful when
budgeting the one paid verification window.

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
