# user — identity & account domain

Shared **identity aggregate** for the auth area. `customer` (social) and `seller` (local) are
**auth flows** that create / look up users here via `user-api`. Root `AGENTS.md` owns the
cross-cutting rules; this file is the user-specific + **auth-area shared map** only.

## Status

- ✅ account creation + lookup via `UserAccountUseCase` (social customer / local seller).
- ✅ signup verification (phone / email), terms agreement history, profile image (store / replace / remove), withdrawal request.
- 🚧 grade / order linkage — not built.

## Where things live (auth-area map)

| Concern | Owner |
|---|---|
| `User` aggregate, role/status/grade/gender, `ProviderType` | **user-core** (record); shared types published in **user-api** (`user.model.*`) |
| Create / find a user | **`UserAccountUseCase`** (user-api port) — `RegisterSocialCustomerCommand` / `RegisterSellerCommand` |
| `User` persistence | user-core (JPA, `db-core`) → `user_schema` (DDL: `db/migration/user/`) |
| JWT issue / verify, `@CurrentUserId` | **`common/web`** — not a domain |
| Refresh / blacklist store | **`common/web`** interfaces (`RefreshTokenStore`, `AccessTokenBlacklist`) ← **`common/auth`** Redis impl |
| Login / logout / refresh **orchestration** | the **flow module** (seller / customer), *not* user |

Token lifecycle rules shared by seller and customer (`JwtTokenLifecycle`):

- **Logout takes the refresh cookie only** — revokes that sid, so it works after the access token expired.
  Public route, idempotent 204 even with no/invalid cookie. Seller and customer share the refresh cookie name,
  so a token whose role isn't the calling flow's is left alone (`revokeSessionByRefreshToken`).
- **Refresh reuse grace** — a jti replaced by rotation within `jwt.refresh-token-reuse-grace`, presented
  again, gets a new access token only (no rotation, no revoke): tabs reissuing at once. Outside the window it
  is reuse → sid revoked. Accepted risk: a stolen replaced refresh replayed inside the window gets an access
  token. The session key keeps the plain current jti so pods without the grace script and rollbacks stay
  compatible (`RefreshTokenRedisRepository`).

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

- Signup-verification and profile-image failures are `UserErrorCode` (user-core). Domain record guards
  (`User.createSocialCustomer`, `User.createSeller`, `User.updateNickname`) still use `Assert` /
  `IllegalArgumentException`.
- Flow modules can't see user-core, so a code they must branch on is published as a constant in
  `UserPublicErrorCodes` (user-api) and `UserErrorCode` reuses it — never re-type the string.

## Profile image

- DB holds an **object key only**, never a URL (`ProfileImagePublicUrlResolver` rejects URLs).
- Storage I/O runs **outside** any caller transaction (`UserAccountService` profile-image methods are
  `NOT_SUPPORTED`) — an upload or delete can't be rolled back.
- **Accepted loss:** after a hard delete commits, a failed S3 delete is logged and not retried, so the
  object stays in S3 (`UserProfileImageCleanupService`). Retry is a separate ticket.
- `UserProfileImageCleanupUseCase` deletes only keys under that user's path.
- batch-app needs S3 settings to boot on purpose: the hard-delete job must clean images, so a missing
  S3 config fails fast instead of silently leaving them.

## Tests

`user-core/src/test` — domain (`UserTest`) + service tests (Mockito on ports), fixed `Clock` via
`TimeProvider`. Run: `./gradlew :modules:user:user-core:test`.

## Security

Auth attack surface (authz/IDOR, token lifecycle, PII, OAuth) is owned by the **security-reviewer**
agent — see `.claude/review/security-reviewer-methodology.md`. Don't restate it here.
