# user — identity & account domain

Shared **identity aggregate** for the auth area. `customer` (social) and `seller` (local) are
**auth flows** that create / look up users here via `user-api`. Root `AGENTS.md` owns the
cross-cutting rules; this file is the user-specific + **auth-area shared map** only.

## Status

- ✅ account creation + lookup via `UserAccountUseCase` (social customer / local seller).
- 🚧 profile / grade / order linkage — not built. **Auth/login is the only flow so far.**

## Where things live (auth-area map)

| Concern | Owner |
|---|---|
| `User` aggregate, role/status/grade/gender, `ProviderType` | **user-core** (record); shared types published in **user-api** (`user.model.*`) |
| Create / find a user | **`UserAccountUseCase`** (user-api port) — `RegisterSocialCustomerCommand` / `RegisterSellerCommand` |
| `User` persistence | user-core (JPA, `db-core`) → `user_schema` (DDL: `db/migration/user/`) |
| JWT issue / verify, `@CurrentUserId` | **`common/web`** — not a domain |
| Refresh / blacklist store | **`common/web`** interfaces (`RefreshTokenStore`, `AccessTokenBlacklist`) ← **`common/auth`** Redis impl |
| Login / logout / refresh **orchestration** | the **flow module** (seller / customer), *not* user |

> **Cross-domain rule (ArchUnit-enforced):** customer/seller depend on **`user-api` only**, never
> `user-core`. So shared value types (`UserRole`, `UserStatus`, `UserGrade`, `UserGender`,
> `ProviderType`) live in **user-api**, not user-core.

## Outbound events (this domain owns the wire)

`UserEventPublisher` (user-core port) → `RedisUserEventPublisher`. Channel names live in
`UserRedisKeys`.

| Channel | Payload | Fired from |
|---|---|---|
| `user:account:withdrawing` | `{"userId":"<uuid>","requestedAt":<epochMillis>}` | `UserAccountService.requestWithdrawal`, **`afterCommit`** |

- **The wire shape is ours; subscribers adapt.** They cannot reference `UserRedisKeys`
  (`-core` is never depended on), so each holds its own string — renaming a channel **silently breaks
  every subscription** (publish succeeds, nobody receives). Same position `LiveRedisKeys` is in.
- **Publish after commit, never inside.** Inside the transaction a rolled-back request still goes out and
  subscribers act on a healthy account. The hook skips (with a warning) when no synchronization is active,
  so a call from outside a transaction publishes nothing rather than publishing unguaranteed.
- **At-most-once, and there is no backstop.** The adapter swallows publish failures — throwing from an
  `afterCommit` hook would surface an already-committed request to the caller as a failure. Nothing replays a
  missed event, so what a subscriber loses is permanent for that event. Today's only subscriber is chat, which
  closes the member's open WebSocket sessions; a missed publish leaves those alive until the broadcast ends.
- The request path also calls `revokeAllSessions`. That is a **different axis**: it blocks re-entry, while an
  already-established WS session never re-reads the token. Don't treat one as covering the other.
- `UserStatus.SUSPENDED` has **no event** — the same gap withdrawal had. Add one the same way if suspension
  ever needs to reach other domains.

## Invariants (domain record)

- Role on creation: social signup → `USER`; local signup → `SELLER`
  (`User.createSocialCustomer` / `createSeller`). Don't set role ad hoc.
- **email is immutable** (excluded from profile update). **nickname** changes are limited by the
  customer/seller auth flow policy; `User.updateNickname` only applies the already-validated change.
- Transitions return a **new** `User` (immutable record); never mutate fields.

## Errors

`user` has **no own `ErrorCode` enum** — domain record guards (`User.createSocialCustomer`,
`User.createSeller`, `User.updateNickname`) use `Assert` / `IllegalArgumentException`, mapped by the
global handler. Persistence entities do not own domain validation; they keep JPA mapping and mutation
only. The flow modules own the catalogs (`SellerErrorCode` / `CustomerErrorCode`). Add a
`UserErrorCode` only when user gains domain-specific failures (e.g. status transitions).

## Tests

`user-core/src/test` — domain (`UserTest`) + service tests (Mockito on ports), fixed `Clock` via
`TimeProvider`. Run: `./gradlew :modules:user:user-core:test`.

## Security

Auth attack surface (authz/IDOR, token lifecycle, PII, OAuth) is owned by the **security-reviewer**
agent — see `.claude/review/security-reviewer-methodology.md`. Don't restate it here.
