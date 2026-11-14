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
  - **`chat` is an exception, by design.** It has no schema of its own: `chat_kick_log`, `chat_ban`,
    `chat_ban_release` and `chat_filter_word` live in `live_schema` and their DDL is in
    `db/migration/live/`, so **live owns those migrations even though chat owns the tables**.
    `migrate.sh`'s `SCHEMAS` has no chat entry. A chat-side change to those tables goes through the
    live owner.
  - ⚠️ **`chat_ban_release` is read on the ordinary kick path**, so its migration leads the app the same
    way shedlock's does (below). The ban-escalation count reads an amnesty baseline from it through a
    correlated subquery, and that count runs on every first-kick-in-a-room — so if the app ships first,
    `POST /api/v1/lives/*/chat/kick` fails with `relation "chat_ban_release" does not exist`, not just the
    new release endpoint. No extra guard was added: this fails loudly with a 500 rather than silently.
  - **Mongo is outside Flyway entirely.** `chat_messages` and its indexes are created at boot by
    `ChatMongoConfig`, which only runs in **streaming-app** (its argument is a `ReactiveMongoTemplate`,
    which live-app does not have). So the indexes behind live-app's chat-history endpoint depend on a
    *different* app having booted. Nothing in this file used to say so.
- **No cross-schema FK** — domains reference each other by `uuid` id columns only, so schemas
  have no apply-order dependency (the runner loops schemas in any order).
- **Run on PRIMARY only** (replicas get it via WAL). `outOfOrder=true`, so timestamp versions
  applied out of branch-merge order still take.
- **Version naming: `V<yyyyMMddHHmm>__description.sql`** (current `V1__init_*` are the seed DDL).
- **Adding a schema:** one `folder:schema` line in `migrate.sh`'s `SCHEMAS` list **+** create
  `db/migration/<folder>/`. Nothing else.

- ⚠️ **An applied migration file is frozen — comments included.** Flyway's checksum covers the whole
  file, so editing a comment in a file that has already run is the same as editing its DDL. `migrate.sh`
  passes no `-validateOnMigrate=false` / `-ignoreMigrationPatterns`, so validation is on by default: a
  mismatch aborts that schema, and `set -eu` means the schemas after it never run either. The runner is
  a **Helm pre-upgrade Job**, so this stops the deploy.
  - **The test for "may I edit it" is whether it exists on a remote branch**, not whether the change
    looks harmless: `git branch -r --contains <the commit that added the file>`. Empty ⇒ it has run
    nowhere, edit freely. Non-empty ⇒ frozen; put the correction in the **next new migration file**
    (`V1__init_live.sql`'s stale escalation comment is recorded in `V202609061437` for exactly this reason).
  - **Testcontainers cannot catch this, structurally** — those tests run Flyway against an empty database,
    where a mismatch cannot arise. Build, ArchUnit and the harness scripts all stay green while a frozen
    file is edited. There is no gate here; the rule is the gate.
  - If a database already applied the older bytes, `flyway repair` rewrites the stored checksum. That is
    recovery, not permission — it is still the frozen-file rule that decides whether the edit was allowed.
  - Measured on 2026-11-05 (SPR-165): a 6-line comment edit to `V202609061437__chat_ban_one_row_per_user.sql`
    moved its checksum from `-721420016` to `-1766037485`. The commit that added that file is on `origin/dev`.

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
  lock table has no entity.
  **The app context tests do not cover this.** They set `live.reconcile.enabled=false`
  (`LiveAppContextFixture`) because the cleanup jobs would otherwise call LiveKit for real, and the guard
  is `@ConditionalOnProperty(live.reconcile.enabled=true)` — so it is never created there and
  `contextLoads` stays green on a local database that predates the migration. The guard fires on a real
  run (`bootRun` / deployed pod), which is where it matters; locally, run `migrate.sh` against your DB, or
  set `live.reconcile.enabled=false` to take the jobs down with it.
- **Removed live-app config keys must leave every deployed yaml/env before the upgrade**, or the app refuses
  to boot → `.claude/rules/live/reconcile.md`.
- **Before a bulk UPDATE of `live_schema.live_room.status`, disable the orphan-media job and rolling-restart
  first** → `modules/live/AGENTS.md` §Design-time invariants.

How it runs:
- **local:** `DB_URL=… DB_USER=… DB_PASSWORD=… ./infra/migration/migrate.sh` (needs Flyway CLI;
  auto-detects `db/migration` from repo root).
- **docker:** image built from `infra/migration/Dockerfile`.
- **prod:** same image run by a Helm pre-upgrade Job (wired when prod is promoted).

## Audit-log queries (operator script, measured SPR-175)

`infra/audit-query/query-audit-log.sh` is the read path into `live_schema.audit_logs` — the table
chat's audit rows land in, because chat has no schema of its own (see Migrations above). It only
ever runs `SELECT`; there is no write mode, so it cannot be the tool that corrupts the evidence.

- **Three query modes** (`actor` / `action` / `target`), plus `--explain` for the plan and
  `--self-test` for the pure logic. **`actor` requires `actor_type`** — a rejection, not a missing
  optional argument. Measured at 400k rows on `postgres:16`: `actor_id` + `actor_type` pinned →
  **0.088ms, Index Scan Backward**; `actor_id` alone → **13.0ms** — **148×**, because the index is
  `(actor_id, actor_type, created_at)` and dropping the middle column costs the ordering too.
  `action` → 0.072ms, exact equality only (no `LIKE`, so `ChatAuditAction`'s `action LIKE 'CHAT_%'`
  remark describes psql by hand, not this script). `target` has **no index** → 9.9ms; it still runs,
  and the integration test deliberately does **not** assert its plan, so adding an index later is
  not a test failure. **There is no `detail` (jsonb) mode at all** — 15.8ms measured and no plan
  shape to pin, which is why it was left out; an earlier version of this section wrongly listed it
  as a supported path.
- **Exit codes**: `0` success — **including 0 rows**, which is an answer ("that action is not in the
  log"), not a failure. `64` (`EX_USAGE`) usage or argument validation. `12` `--self-test` failure.
  Anything else is **psql's own code passing through** (2 connection failure, 3 `ON_ERROR_STOP`).
  - ⚠️ **`2` is deliberately not used for validation, and this is load-bearing for the tests.**
    Measured: with validation on `2`, a connect failure and a typo were *the same code*, and the
    argv-layer test cells — which run before the container exists — passed because psql could not
    connect, not because a guard fired. Denominator 0 on every one of them. `64` is a code psql
    does not emit, so those cells now measure the guard. One cell asserts the separation directly.
  - The sibling has a `preflight()` → `exit 1`; this script has none, deliberately. The sibling
    generates `UPDATE`s across repeated rounds, so not starting on bad input matters; a read-only
    one-shot gets the same information from psql's own error text.
- **The row count never hides truncation — on the snapshot axis.** It counts to `limit + 1` and says
  `N건 이상 — 상한 N 에서 잘렸다` rather than printing a capped number: at the cap, "exactly 50" and
  "the first 50 of many" are different answers to an audit question. Counting the whole predicate
  instead would unbind the LIMIT the plan above depends on. The probe has no `ORDER BY`, so the
  plan `--explain` pins is the row query's, not the probe's; LIMIT is what bounds the probe.
  - ⚠️ **That sentence held on the snapshot axis and failed on another one.** With a leading zero
    in `limit`, the probe read the value in a different base from the row query, so the count was
    wrong *and* the truncation warning disappeared — the exact property this bullet claims. The
    guard below closes it; the lesson is that "never hides X" wants the axis named.
- **Credentials never in `argv`** (ps-readable) — libpq env vars / `~/.pgpass` / `PGSERVICE`, same
  position as `replay-backfill`. ⚠️ **libpq prefers the service file over env vars**, so a shell set
  up for production reaches production; `integration-test.sh` `unset`s
  `PGSERVICE PGSERVICEFILE PGOPTIONS PGHOSTADDR PGSSLMODE PGPASSWORD` **as its first statement**,
  before any cell runs — measured: while that line sat lower in the file, the connect-failure cell
  above it set `PGHOST`/`PGPORT` by hand, the service file won anyway, and with a reachable service
  file the cell both reported FAIL and sent its `SELECT` to that database. Every
  psql call passes `-X`, so a developer's `~/.psqlrc` cannot reshape operator output or the count.
- **`limit` is the only value interpolated into SQL as a shell string.** Everything else goes
  through psql `-v` variables with the SQL on **stdin** — `psql -c` does not interpolate `:'var'`
  at all. What that claim rests on: `mode` is a three-item whitelist and `v1`/`v2` go through `-v`.
  `$where` is interpolated too, so **a fourth mode that embeds any operator value in its WHERE
  makes the sentence silently false** — a new mode must pass values as `:'vN'`.
  - ⚠️ **This value's real defect was not injection but the numeric base**, and the test comment
    nailing the axis to injection alone is why nothing measured it. Measured: one string is read
    three ways — Postgres `LIMIT 050` → decimal 50, `[ 050 -gt 49 ]` → decimal 50,
    `$((050 + 1))` → **octal 41**. A leading zero therefore printed 50 rows, counted 41, and
    `[ 41 -gt 050 ]` being false **also suppressed the truncation warning**. `08`/`09` are worse:
    `$((08 + 1))` dies with *value too great for base*, and `set -e` exits **1** — a code this
    file's table does not list, and the sibling's `die()` value.
  - **`$((10#$limit + 1))` is not the fix here.** `10#` is a bash/ksh extension, not POSIX
    arithmetic (measured: `dash` → `arithmetic expression: expecting EOF`), and the shebang is
    `#!/bin/sh`. The guard rejects leading zeros instead, so all three readers see one value.
  - It is now **one shape guard plus a ceiling**, not the original two-check pair. Once leading
    zeros are rejected the surviving values are `[1-9][0-9]*`, so the old `-gt 0` check had
    denominator 0 and was removed. Three earlier versions of this bullet were wrong about these
    guards — first claiming a mutation had to remove both, then calling them a message/gate pair,
    then justifying the absence of a ceiling with an error that does not fire.
  - **`limit` is capped at 10000**, and the reason the earlier "no ceiling" line gave was false in
    two places. Measured against 5 rows: `limit=9000000000000000000` → **exit 0, 5 rows, correct
    count** — nothing dies, so "psql's bigint error kills it first" never fires in that band, while
    the row query stops being bounded by `LIMIT` at all; `limit=9223372036854775807` → exit 3, but
    the death is in the **probe**, not the row query, because `$((max + 1))` wraps negative
    (`LIMIT must not be negative`) *after* the rows have already printed — the last face of the
    base/overflow root above; `9223372036854775808` and up → exit 3, `bigint out of range`.
    Nothing is silently wrong in any band, so this is a false justification, not a defect. The cap
    makes all three unreachable and turns "LIMIT bounds the plan" — which every cost figure here
    rests on — from an explanation into an entry condition. **10000 is a policy ceiling, not a
    measured threshold**: this is a window a person reads in a terminal, and an answer bigger than
    that is the wrong question. What the measurements decide is only *that* there must be a cap.
- **Two test layers**, same split the sibling learned: `--self-test` covers the pure argv/validation
  logic with no database and reports how many checks it ran (a self-test that is one `exit 0` is a
  guard with denominator 0); `infra/audit-query/integration-test.sh` covers the rest in 26 cells —
  table/column names, query syntax, real exit codes and their separation, newest-first ordering,
  truncation in both directions, rows-printed matching the cap, both leading-zero vectors, both
  sides of the `limit` ceiling, the usage text listing every mode (and the 148× figure it states,
  so that number cannot drift unnoticed), and the index plans. It applies
  **the whole real `db/migration/live/V1__init_live.sql`**.
  - It also checks `current_database()` and that `live_schema` is empty before running DDL, copied
    from the sibling. ⚠️ **On this script's own path that guard cannot fire** — like the sibling, the
    test starts its own container and exports `PGHOST`/`PGPORT` itself, so the target is always its
    own database. Denominator 0 today; it earns its place only if someone later points the test at
    a database it did not create. Fed a reachable wrong target by hand, both halves do reject.

## Observability ports (live-app)

Two apps now expose metrics on a split management port. The rules below were written for live-app; the
**streaming-app section that follows states where it differs** — most importantly it has no Spring Security,
so the port split is the only thing standing between an anonymous scraper and the endpoints.

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
  so a config slip fails loudly instead of quietly publishing metrics on the user port.
- ⚠️ **"Manifests still have to *set* the management port" is weaker than it reads.** The guard rejects an
  *absent* key, and the shipped template is `port: ${MANAGEMENT_PORT:8091}`
  (`management.server.port` in live-app `application.yaml.example`) — copy that and leave the env var unset and the placeholder resolves to
  8091, so the app **boots normally**. A manifest whose scrape target was pointed at the env value then
  gets silently empty metrics. If you want the env var to be load-bearing, drop the `:8091` default from
  the template; today it is a convenience, not a gate. (streaming-app: same shape,
  `${MANAGEMENT_PORT:8092}` at `management.server.port` in `application.yml.example`.)
- **It also refuses to start unless `health` and `prometheus` are *actually exposed*** (added 2026-10-31,
  widened 2026-11-01). The guard does **not** read `exposure.include`/`exclude` any more — it asks
  `WebEndpointsSupplier.getEndpoints()`, because six config axes can remove an endpoint and a key-reading
  guard only saw two. Measured, each booting successfully while the old guard passed:
  `endpoint.<id>.enabled=false`, `endpoints.enabled-by-default=false`, `endpoint.<id>.access=none`,
  `endpoints.access.default=none`. The two *default*-scoped ones (`enabled-by-default`, `access.default`)
  leave **no endpoints at all**; the two id-scoped ones remove just that endpoint. Actuator's
  default exposure is `health` alone, so omitting that line leaves no metrics endpoint at all; the path then
  misses the actuator chain, falls to `authenticated()`, and the scraper gets **401** — metrics silently
  empty, indistinguishable from "no broadcasts". Measured: the first CI run of the port test failed exactly
  that way, which is also how the gap was found. The template no longer calls that line optional.
- **The context tests for live-app now run in CI** (`LiveAppApplicationTests`, `ActuatorNotOnUserPortTest`).
  They used to be excluded by `@Tag("context")` on the grounds that CI would need the room-token **private
  key** as a secret; that was wrong — the key is a plain property, so the test generates a throwaway keypair,
  Testcontainers supplies Postgres/Redis/Mongo, and Flyway applies the real migrations. Two facts surfaced the
  moment they ran: the exposure line above, and that `/actuator/health` depends on **Mongo** (503 without it,
  because the kick-evidence read lives there) — so a manifest that omits Mongo makes this app look unhealthy.
- **Deployment prerequisite — read before merging live-app.** `management.server.port` must *resolve*
  before this app is rolled out, and `health` + `prometheus` must actually be exposed: without either the
  app refuses to start by design.
  - ⚠️ **`MANAGEMENT_PORT` itself is not load-bearing today**, and an earlier version of this bullet said
    it was. The guard rejects an *absent* key, and the shipped template is `${MANAGEMENT_PORT:8091}`
    (`management.server.port` in live-app `application.yaml.example`) — copy that, leave the env var unset, and the placeholder resolves to
    8091 and the app **boots**. A manifest that points its scrape target at the env value then gets
    silently empty metrics. To make the env var a real gate, drop the `:8091` default from the template.
  - Developers running locally need the same keys; copy the `management` block from
    `application.yaml.example`.
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
- **SPR-148 URL contract:** `startHlsEgress` returns separate live/archive URLs; `hls_archive_url`
  retains the archive URL across start, reload and end. Production (no `MasterPlaylistPublisher` bean)
  uses `720p/playlist.m3u8`; future ABR uses `archive-master.m3u8` listing each rendition's EVENT playlist.
  Existing rows are not automatically rewritten: verify the stored object's EVENT playlist and all
  referenced segments before correcting that row's URL. A legacy `master.m3u8` needs a published archive
  master or a verified 720p EVENT URL; a filename-only master replacement cannot create the missing object.
- **Replay is fully public (SPR-148 decision).** Shared unsigned prefixes are intentional; no permission
  check, presigned URL or archive prefix is required. A public origin is allowed, and the previous
  requirement to settle archive placement before CloudFront is removed. The SPR-147 measurement (public
  access blocked, direct HTTP 403, no CloudFront distribution) is historical; URL correctness alone does
  not make an unchanged private bucket publicly playable. Public delivery remains infrastructure work.
  Before enabling replay in the client, verify anonymous GET of the API-returned manifest and every
  referenced variant/segment through the configured delivery URL. API 200 alone is not a rollout check.
  Knowing a live URL can reveal the full archive by changing the filename, and viewers can keep downloaded
  copies. This is an accepted consequence of the ticket's explicit full-public replay requirement, not a
  technical claim that the old threat disappeared. Revoking already downloaded copies is not supported.
- **SPR-148 replay backfill is re-run until it reports completion; one pass is never enough.** Rooms live
  across the deploy only become ENDED after `max_duration_seconds` (default 7200), so a single pass
  misses them. `infra/replay-backfill/backfill-archive-url.sh` reads only — it verifies EVENT/ENDLIST and
  every listed segment and emits per-row SQL for passing rows; a human applies it with `psql -f`
  (never `psql -1`, which would hold row locks for the whole file). Its exit code is the completion
  condition: `11` SQL was generated but nothing is fixed yet — apply it and run again (this takes priority: skipped rows,
  leftover backlog and unfinished rooms are all reported alongside it and carry to the next round, so never
  discard the SQL because more work remains); `10` nothing was generated and another pass is still needed
  (skipped rows, backlog past this round's `BATCH_SIZE`, or deploy-boundary rooms not yet ENDED);
  `1` failed input validation or preflight — it checks required inputs, the table/columns and that
  `CDN_BASE_URL` is reachable (the root's HTTP policy does not determine the objects' policy) rather than
  reporting an empty result as success (a psql failure in a later query exits with psql's own code, and
  `--self-test` failure exits `12`); `14` an actual manifest/segment redirects or a segment Range response
  exceeds the size cap — fix its delivery configuration,
  do not advance the cursor or follow redirects blindly. Some SQL may already have been generated;
  review it separately. `13` this round is done but rows remain stuck before the cursor — **not**
  complete, and deliberately distinct from `0` so that pushing the cursor to the end cannot read as success; `0` only when nothing was generated, no candidates remain, no deploy-boundary room
  is still running **and** nothing is stuck before the cursor. "Unfinished" means rooms still LIVE that started **before** `DEPLOY_TS` — which is the moment the **last
  pre-SPR-148 replica went down**, not when the rollout began; an old replica can start a broadcast mid-rollout
  and those rooms would fall outside an earlier boundary. Scheduled rooms are excluded (they will be written
  correctly), and suspended rooms are reported separately rather than counted, since nothing ends them
  automatically and one of them would make the condition unreachable. Both the unfinished count and the
  candidate count are taken again after the verification loop; the suspended report is refreshed between
  them, so a LIVE→SUSPENDED transition is not silently omitted. Rows that appeared during a long round are
  not missed. Both populations are in scope: `hls_archive_url IS NULL`
  (deploy boundary) and `hls_archive_url = hls_url` (legacy copies); fixing one hides the other. ENDED rows
  with no `hls_url` at all cannot be derived and are only reported, never guessed. A row whose archive is permanently unverifiable would otherwise block the head of every round, so a round that
  made no progress prints the last `ended_at` it examined; pass it back as `AFTER_ENDED_AT` to advance past the
  stuck block. Those rows are listed by id and counted separately as stuck-before-cursor; if only those
  remain, the exit is `13`, never `0`. Clearing them is manual work.
  Playlists are capped at 1MB on fetch. At 7200 one-second segments, filenames plus EXTINF alone take
  about 225KB; PROGRAM-DATE-TIME and other tags add more. The cap is an operational limit, not a
  sevenfold size guarantee; exceeding it is a
  permanent failure, not a transient one. Run `--self-test` for the pure logic (derivation, escaping, exit-code selection, playlist verification via a
  stubbed curl, literal drift against the Java sources) without a database. A copied standalone script
  warns that the three Java comparisons were not run; its passing logic tests are not complete deployment
  verification. Run the matching repository version's self-test before copying it to a bastion. Run
  `infra/replay-backfill/integration-test.sh` for the rest: it starts PostgreSQL with the real `live` migration
  and a local HTTP origin bound to loopback, seeds the row shapes that matter (legacy copy, NULL archive,
  `-master` URL, missing segment, room live across the boundary, and the three report-only populations) and
  asserts the generated SQL, the exit code of every round, the conditional-UPDATE guard refusing a row that
  changed after generation, cursor round-tripping, both failure origins — a dead server (curl 7) and a 5xx origin, which are classified
  differently — and a room that transitions to ENDED between the two post-loop counts, which is the case the
  order of those counts exists to close. The integration test's psql wrapper inserts that delay;
  production rejects the removed `BACKFILL_PAUSE_*` variables before producing SQL. Negative assertions are gated on the round having finished cleanly,
  because "the row is absent" is also true when the script died. Every defect found in this tool so far sat in that layer, not
  in the pure logic.
  Segment checks use a bounded Range GET and require MPEG-TS sync bytes at offsets 0, 188 and 376,
  rejecting ordinary HTML soft-404 responses. Origins ignoring Range are capped at 1MB; larger responses
  are delivery configuration errors (`14`), not missing media and never a reason to advance the cursor.
  This is a format sanity check, not proof of complete or correct media.
  Existence of a key is not verification, and at least one row must decode to EOS in a real player before
  sign-off. Segment lines are required to be bare filenames — a deliberate allowlist, since anything else lets the object
  being verified choose the verifier's next request. The retained SPR-147 artifact was inspected again
  on 2026-09-12: a URI line is exactly `segment__00000.ts`, consistent with the bare-filename policy.
  Unsupported URI forms fail verification; advancing past such rows produces `13`, never completion `0`.
  A `0` exit covers the tool's candidate population only. Before removing legacy hiding, also resolve or
  explicitly account for every report-only population (suspended, no live URL, no ended_at); a suspended
  legacy room may end later. Only after that review, a `0` exit **and** complete retirement of pre-SPR-148 writers may the legacy-hiding
  mapping (`LiveRoomMapper.archiveUrl`, the Ended branch of `applyStatusFields`) be removed, and that
  removal must update `modules/live/AGENTS.md` §Design-time invariants (the legacy-rows line), the mapper
  comment, the
  `copiedLiveUrlIsUnavailableUntilBackfilled` test name and `.claude/review/domains/live.md` LIVE-08 in the
  same commit.

- A failed second master upload may leave the first master object behind. The returned/persisted URLs
  fall back to 720p; never choose an orphan master merely because its key exists during manual recovery.
  Validate every variant and its complete segment list. Publisher registration must include its specified
  timeout tests and an operational orphan-object policy; this ticket adds neither an adapter nor deletion
  calls inside the start transaction. Reversing upload order cannot make two object writes atomic.

### SPR-148 recovery verification (2026-09-11)

- Existing SPR-147 media: room `727682db-4f95-4dc8-835f-556b8f428ec1`, `720p/` rendition.
  Downloaded original S3 objects: `index.m3u8` contains 5 segments / 10s (sequence 215), while
  `playlist.m3u8` contains EVENT + ENDLIST and all 220 segments / 440s (sequence 0).
  All referenced segments downloaded; GStreamer `playbin` decoded the unchanged EVENT playlist
  with audio/video fakesinks through EOS, exit 0, without ERROR/WARNING. No re-encoding is needed.
- This proves media recovery by choosing the EVENT URL, **not a completed row/API recovery**:
  the available SPR-147 local PostgreSQL has no live table, so no existing row was changed.
  Anonymous HTTP to the S3 EVENT object still returned **403**. Verify a real Ended row and public
  delivery before calling the end-to-end recovery complete; do not change bucket policy as part of this ticket.

### Timing budget *(measured)*

`egress start` → first segment in S3: **12-15s** (720p single rendition, 2s segments). Useful when
budgeting the one paid verification window.

## Observability ports (streaming-app)

Same two-port shape as live-app, with one difference that changes the risk: **streaming-app has no Spring
Security at all** (verified: no `SecurityWebFilterChain` anywhere in the app). live-app can afford a
`permitAll` actuator chain because a *second* chain denies everything else; here there is no chain to hang
that on.

- **Default management port is 8092** (`MANAGEMENT_PORT`), chosen to clear api-app 8080 and live-app
  8081/8091. The user port is where **WebSocket handshakes** arrive. "Default" is literal — the template
  carries `${MANAGEMENT_PORT:8092}`, so an unset env var is not a boot failure; see the live-app note above.
- `ObservabilityExposureConfig` refuses to boot unless the management port is set and differs from
  `server.port`, **and unless the set of endpoints that are *actually exposed* is exactly
  `{health, prometheus}`** (widened 2026-11-01). It no longer parses `exposure.include`/`exclude`: it asks
  `WebEndpointsSupplier.getEndpoints()`, so the four other axes that can remove an endpoint
  (`endpoint.<id>.enabled`, `endpoints.enabled-by-default`, `endpoint.<id>.access`,
  `endpoints.access.default`) are covered by the same single question. Measured: each of those booted
  successfully while the key-reading guard passed, and two of them left **no endpoints at all**.
  Before that, a subset check alone passed an *absent* list, and then no `/actuator/prometheus` exists
  (actuator's default exposure is `health` alone): with no security here the scraper gets a 404 rather
  than live-app's 401, and either way the chat metrics are silently empty. live-app already failed boot
  on this; the two apps were asymmetric for one day. The second check does not exist in live-app, because there the security chain
  denies anything outside that pair; here the yaml line is the whole gate, and `"*"` would serve
  `/actuator/env` and `/heapdump` unauthenticated.
  - ⚠️ **Those two are not equally bad, and an earlier version of this file got it wrong.** `/heapdump`
    is a memory image — Mongo URI credentials and the Redis password are in it in the clear, and masking
    is not a concept that applies. `/actuator/env` **masks its values**: measured from bytecode,
    `management.endpoint.env.show-values` defaults to `Show.NEVER` and `Sanitizer.sanitize` then returns
    `"******"` without consulting any `SanitizingFunction`. What it does leak is every property **name**
    and source — a blueprint of which keys exist and which files/env vars are in play.
  - That masking is a **default**, flipped by one line (`show-values`), and no tracked template sets that
    key. So the defense for `env` lives outside the code we review — which is the argument *for* locking
    the list in code, not against it.
- **Unauthenticated on every interface, same as live-app** — so the same conclusion holds: ClusterIP only,
  never behind the ingress, NetworkPolicy to the monitoring namespace where available.
- **Probes**: use `/actuator/health/liveness` and `/actuator/health/readiness`, not the aggregate path, for
  the reason spelled out in the live-app section. Note this app previously had **no health endpoint at all**,
  so a TCP socket check was the only probe available — and that check answered `Ready` while startup work was
  still blocked. A real readiness probe is now possible and the manifest should use one.
  - **Size the `startupProbe` for 10 minutes of nothing, measured.** Boot index creation on
    `chat_messages` runs in an **eager singleton** (`ChatMongoConfig`), which is **before the port
    binds** — so for the whole of that window `httpGet` and `tcpSocket` both get **connection refused**,
    not a false `Ready` (asserted by a raw TCP connect in
    `EagerSingletonsRunBeforeThePortBindsTest`). The window's length is
    **10 min 30 s** (SPR-178): `ChatMongoConfig.INDEX_CREATE_CEILING` = 10 min (SPR-152) is **one
    deadline over all four index creations**, not per call — a fifth *creation* would not add to it —
    and `INDEX_DROP_CEILING` = 30 s sits **outside** that deadline, for the one superseded-index drop. Nothing in the driver bounds
    it otherwise: `socketReadTimeoutMs` defaults to **0 (infinite)** and the repo sets no Mongo client
    timeout anywhere (measured).
    - So `failureThreshold × periodSeconds` on the **`startupProbe` must exceed 10 min 30 s**. Under that and
      Kubernetes kills the pod before the app's own ceiling fires — which throws away the one message
      that tells the operator *which* of the two causes it was (Mongo silent vs. first build on a large
      collection). `livenessProbe`/`readinessProbe` are not the lever here; a `startupProbe` is, because
      it is the only one that suspends the other two.
    - Two different deaths, two different waits: an **unreachable** Mongo address dies at
      **30,053 ms** (measured — driver `serverSelectionTimeout` default, surfaced as
      `DataAccessResourceFailureException`), not at the ceiling. The ceiling only covers the mode where
      a server *was* selected and `createIndex` does not answer.
    - Dying at the ceiling reads as `CrashLoopBackOff` rather than one loud failure — but **the restart
      does not re-fire the build; the server-side build survives and the new call waits on it** (measured
      on **standalone mongo 7** with the `hangAfterInitializingIndexBuild` failpoint: a second identical
      `createIndexes` **blocks** instead of returning `IndexBuildAlreadyInProgress`; a hard client
      disconnect leaves the server-side build in place — `createIndexes` op still listed,
      `IndexBuildsCoordinatorMongod-0` still running — and the index finishes with no client attached).
      So restarting does not undo progress and the loop is **not permanent**: a build taking N× the
      ceiling costs roughly N restarts, and the wall clock is N × 10 min 30 s **plus Kubernetes'
      `CrashLoopBackOff` backoff**, which grows 10s → 20s → … up to a 5 min cap between restarts.
      - Scope, because this claim keeps getting repeated: **standalone**. A replica set builds in phases
        with a commit quorum and DocumentDB is a compatibility layer, and this repo does not record which
        one production runs — so neither is covered. "Blocks" is also not the same as "joins": the
        measurement cannot tell a registered joiner from a lock wait, and the conclusion rests on the
        survival half alone.
      - It is a full outage for that pod while it lasts, so the fix is unchanged: build the indexes out
        of band, or raise the ceiling to cover the build.
- **Deployment prerequisite**: the set of endpoints that are **actually exposed** must be exactly
  `{health, prometheus}` — without that the app refuses to start, by design (`health` missing means the k8s
  probes 404; `prometheus` missing means the metrics are silently empty).
  - ⚠️ **Exactly**, so **adding one more endpoint is also a boot failure** — `include: health,prometheus,info`
    looks reasonable and CrashLoopBackOffs (measured: `더 열린 것: [info]`). An earlier version of this
    bullet stated only the containment half, which is what an operator would follow.
  - The guard does not read `exposure.include`/`exclude`. Keys to check when it fires:
    `exposure.include`/`exclude`, `endpoint.<id>.enabled`, `endpoints.enabled-by-default`,
    `endpoint.<id>.access`, `endpoints.access.default`. `MANAGEMENT_PORT` is **not** in the same category: the template defaults it, so an unset env var
  boots (see the bullet above). The template no longer calls that line optional (it did for one day, while the guard already
  required it — follow that stale template and the pod CrashLoopBackOffs). Developers running locally copy
  the `management` block from `application.yml.example`.
- **What the metrics carry** — **four kinds**, and they want different alerts. (This said "two" while three groups were already listed below, and the fourth arrives with SPR-179; the count is the kind of number that drifts because nothing reads it.)
  - **Counters that are 0 when healthy**: `chat.policy.open`, `chat.ratelimit.degraded`,
    `chat.delivery.dropped`, `chat.session.cut`, `chat.dictionary.fastpath.lost`,
    `chat.persist.timeout`, `chat.send.refused`. Every one of them is a *policy not being enforced*
    right now — or, for the last, sends refused because the policy could not be read — so any
    non-zero rate is worth watching. `chat.send.refused` counts failed reads per `policy`, not
    refused sends (one send can raise kick and ban together); alert on the max over `policy`. Details in `modules/chat/AGENTS.md`.
    - ⚠️ **`chat.dictionary.fastpath.lost` was missing from this list** until SPR-177, while
      `ChatMeterNames` had declared it since it was added — the discipline is "add a line when adding
      a meter" and that line was never added. Whoever reads this list as the set of chat counters was
      reading one short.
    - ⚠️ **`chat.persist.timeout` is not the same axis as `chat.delivery.dropped`** and must not be
      summed with it. `delivery.dropped` means *the message is persisted and only live delivery was
      lost*. Reading them as one number says "it is in the history" about messages that are not.
    - ⚠️ **`chat.persist.timeout` has a `stage` tag whose two values must not be summed either**
      (SPR-178). `stage=write` means **the write's result is unknown**; `stage=duplicate_recovery_read` is the
      duplicate-recovery read, whose premise is that the document **already exists** — the message is
      in the history and only reading it back was cut. Alert on `write` for **a persist whose result is
      unknown** — ⚠️ **not** for confirmed data loss: a client-side timeout does not cancel the
      server's write, and both branches surface as the same driver exception, so the caller cannot
      tell "nothing was stored" from "stored but unconfirmed" (measured on mongo:7.0 with driver
      5.6.5: a 1ms/2ms bound threw with **no** document, a 3ms/5ms bound threw while the document was
      **present**). The claim that `write` means nothing was stored had spread widely and was corrected
      under SPR-180. ⚠️ **No count is given here on purpose.** Two earlier versions of this line each
      stated one ("four", then "ten, all corrected") and both were short: `PersistStage.WRITE` — the tag
      enum this runbook sends you to — was never on the list, because the sweep matched `아무것도 남지`
      while that file reads `아무 데도 남지`, and two more survived because the filter dropped sentences
      already narrowed to the write branch, which is precisely what made them false. **A count is itself
      a claim.** The reproducible statement is the sweep: match on the *subject* (`stage=write`,
      `PersistStage.WRITE`, `PERSIST_TIMEOUT`) and read every hit, rather than on one phrasing of the
      predicate.
    - **What to do when `write` fires**: do not report data loss. Check whether the message is in the
      history. Do not tell the user to retype the message: the client resends with the *same*
      `clientMsgId` and body ("Retry contract (SPR-188)" in `modules/chat/AGENTS.md`), which the
      unique partial index deduplicates if the first write landed. A retyped message is a new UUID and can duplicate.
      Also alert on
      `duplicate_recovery_read` for "the sender cannot see a message that is already stored"; they
      need different responses. Details in `modules/chat/AGENTS.md`.
  - **Counters that are 0 when nobody is abusing** (added SPR-152): `chat.sessions.admission_capped` ·
    `chat.sessions.reaped` — the per-`(user, room)` session cap denying a connect, and the same cap
    reclaiming a slot whose socket had already gone silent. These are a different kind from the four
    above: a non-zero rate there is a policy *failing*, here it is a policy **firing**. Watch them as a
    **pair** — denials rising while reclaims stay flat reads as abuse, or as a cap set too low; both
    rising together reads as clients dropping sockets without closing them, which is ordinary mobile
    behaviour. Neither question can be answered from `chat.session.cut{liveness}`, whose only tag is the
    reason.
    - **"A cap set too low" is now actionable from config** (SPR-176): the cap is
      `chat.session.max-per-room`, and it takes effect on restart — it is a constructor argument, not
      `@RefreshScope`. **A value below 1 fails boot by design** rather than quietly running an uncapped
      pod. ⚠️ That guard covers `< 1` **only** — `500`, or a misspelled key falling back to the default,
      both roll out fine, so it does not make every typo visible. There is no upper bound; a very large
      value is how you ask for "no cap".
      Before raising it, note the derivation in `modules/chat/AGENTS.md`: live sessions per
      `(user, room)` can briefly reach **2× whatever you set**, so a cap of 20 is a transient 40.
      - **Read the cap off `chat.sessions.max_per_room`, not off this file.** That gauge exists because
        the pair above *stops being readable* at a large cap: both counters flatten to 0, and that is the
        same picture as healthy. It is also the only way to see a rolling deploy where replicas disagree —
        without it, two different denominators get summed into one graph.
  - **Gauges that are *not* 0 when healthy** (added 2026-11-01): `chat.sessions.local` ·
    `chat.rooms.local` — this pod's live WebSocket sessions and the rooms it relays. These are capacity
    signals, not failure signals, so "non-zero = problem" does not apply; they want a **threshold**.
    They exist because this app has **no connection-count cap** (frame size, outbound buffer and slow
    clients are all bounded; the count is not), and a cap cannot be chosen from a number nobody can see.
    App-side cost per session is measured at **2,349 bytes** (12,000 sessions ≈ 26.9 MB) — *with an empty
    outbound buffer*, so a pod with backed-up sessions costs more — which means the app side is not the
    constraint and the cap has to come from transport-side load testing. Order: gauge → threshold alert
    → cap.
  - **Counters whose 0 is ambiguous** (added SPR-179): `chat.sessions.heartbeat_failures` — the session
    registry's re-registration failing. It belongs to none of the three groups above: not a policy being
    let through, not a policy firing, not a capacity reading. It is an infrastructure failure, and the
    consequence is severe — if re-registration stops, **every live viewer drops out of the count** after
    the staleness window (4 × the heartbeat interval).
    - ⚠️ **Do not read 0 as healthy.** A round that *fails* is counted; a round that never *finishes* is
      not, and the registry's own javadoc records that branch as the worse one. So this counter is
      evidence that re-registration **is failing** — never evidence that it is working. A stall reads as
      `chat.sessions.heartbeat.skipped` climbing (SPR-199); `chat.sessions.heartbeat.duration` times only
      completed rounds, so a stuck round never appears in it.
    - Before this meter existed the only signal was a WARN thinned to one line per 10s, and that latch is
      **shared** with two other failure modes, so whichever fires first hides the others' first
      occurrence. Details in `modules/chat/AGENTS.md`.
    - `chat.subscription.resubscribed{subscription}` — a Redis pub/sub subscription retrying its establish.
      Same ambiguity, different shape: re-subscription is a `retryWhen` loop, so **a recovered drop climbs a
      few times and stops while a wedged subscription climbs forever**. Alert on "still climbing after N
      minutes", not on non-zero. 0 also covers "never established", which this counter does not answer.
      Measured (SPR-179): the production shape retried twice and recovered when the `PSUBSCRIBE` establish
      reply was delayed past the 1s command bound — so treat permanent death as a shape to watch for, not
      the default. Per-subscription tag because `chat_pubsub` losing its stream silently drops every chat
      delivery and every KICK notice on that pod, while `profanity_dictionary` only ages the dictionary.
- **Not done yet in this repo**: as with live-app, the manifests live in the infra repo, so none of the
  network restrictions above are enforced from here.

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
