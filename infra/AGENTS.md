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
  removal must update `modules/live/AGENTS.md`, the mapper comment, the
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

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
