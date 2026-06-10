# seller — local auth flow

Email + password authentication. Produces a **SELLER** `User` via `user-api`, and owns only the
local password credential. Shared auth-area map + identity invariants: see `modules/user/AGENTS.md`.

## Status

- ✅ signup / login / logout / refresh / duplicate-check (email · phone · nickname).
- 🚧 seller profile / store / settlement — not built.

## Owns

- **`LocalCredential`** (JPA, seller-core): `passwordHash` (Spring `PasswordEncoder`),
  `failedLoginCount`, `lockedAt`, `lastChangedAt`.
  Maps to `seller_schema.local_credentials` (DDL: `db/migration/seller/`; PK column `users_id`,
  no `created_at`/`updated_at` — doesn't extend `BaseEntity`).
- user-core owns the `User`; seller owns **only** the local credential row.

## Conventions

- Create/look up the user via **`UserAccountUseCase`** (`RegisterSellerCommand`, role = SELLER) —
  never `new User`.
- Login / logout / refresh are orchestrated **here**, using `common/web`
  (`JwtTokenProvider`, `RefreshTokenStore`, `AccessTokenBlacklist`).
  Logout = delete refresh + blacklist access (remaining TTL).
- Duplicate-check rules repeat across params and DTOs → prefer **composed constraints**
  (`@Email` / `@Nickname` / `@PhoneNumber`) over stacking `@NotBlank`+`@Pattern` everywhere.
- Login failure returns an **identical ambiguous message** (account-enumeration); lockout must be
  **actually enforced** (`failedLoginCount` / `lockedAt`).

## Errors

Throw `SellerException` (→ `SellerErrorCode`, `SELLER-0xx`) — never raw `IllegalState`/`RuntimeException`
from services. Login failure uses one ambiguous code (`INVALID_LOGIN_CREDENTIALS`) to avoid account
enumeration; the nickname cooldown surfaces as `NICKNAME_CHANGE_RESTRICTED`.

## Tests

`seller-core/src/test` — service tests (Mockito on ports), FixtureMonkey fixtures, fixed `Clock` via
`TimeProvider`. Run: `./gradlew :modules:seller:seller-core:test`.

## Security

- **Posture: deny-by-default.** The seller chain (`/api/v1/sellers/auth/**`) ends in
  `anyRequest().hasRole("SELLER")`; only `SELLER_PUBLIC_MATCHERS` (in `ApiSecurityConfig`) is public
  (signup, check-email/phone, check-nickname, login, token/reissue). A new seller endpoint is
  **auto-protected**. Source of truth = `ApiSecurityConfig` — don't duplicate the list here.
- **Intentional:** `login` / `token/reissue` return the access token (body / `Authorization`) and the
  refresh token (cookie). By design — **reviewers must NOT flag this as token leakage.**
- Full attack surface: `.claude/agents/security-reviewer.md` (authz, token lifecycle, brute-force, PII).
