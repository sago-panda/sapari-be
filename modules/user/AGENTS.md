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

> **Cross-domain rule (ArchUnit-enforced):** customer/seller depend on **`user-api` only**, never
> `user-core`. So shared value types (`UserRole`, `UserStatus`, `UserGrade`, `UserGender`,
> `ProviderType`) live in **user-api**, not user-core.

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
