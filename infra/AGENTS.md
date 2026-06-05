# infra — runtime topology & deploy

Infra/deploy context for sapari-be. Loaded only when working on infra, deploy, or CI/CD.
For application code conventions, see the root `AGENTS.md`.

## Runtime Topology

- Each `apps/*` deploys as a separate Pod on k3s.
- **Sync calls**: in-cluster REST via Service DNS. Cross-domain calls go through `*-api` interfaces.
- **Async messaging**: Redis Pub/Sub (broadcast start/end fan-out, etc.).
- **Shared state**: PostgreSQL · Redis · OpenSearch · S3 — all Pods point to the same instances.
- **CI**: GitLab CI · **CD**: ArgoCD (auto-sync on dev, manual approval on main).

## Manifests & Pipeline

- Infra manifests and pipeline details live in the infra repo.
- (Add k3s manifests, ArgoCD app definitions, and GitLab CI pipeline notes here as infra work begins.)
