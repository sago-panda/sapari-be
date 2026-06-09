# customer — social (OAuth) auth flow

OAuth social login. Produces a **USER** `User` via `user-api`. **No DB of its own** — user
persistence is delegated to `user`; only transient signup state lives in Redis. Shared auth-area
map + identity invariants: see `modules/user/AGENTS.md`.

## Status

- ✅ social login / signup / logout / refresh.
- 🚧 customer profile / grade / preferences — not built.

## Owns

- OAuth flow (`spring-boot-starter-oauth2-client`). **No JPA** — build has no `db-core`; if you reach
  for an entity here, the design is wrong (persist via `user`).
- Transient signup state in **Redis** (single-use, TTL'd, unguessable id).

## Conventions

- Create the user via **`UserAccountUseCase`** (`RegisterSocialCustomerCommand`, role = USER) —
  never `new User`.
- Provider value is validated (`INVALID_OAUTH_PROVIDER`); OAuth state / redirect validated.
- Login / logout / refresh orchestrated **here**, using `common/web` JWT + token stores
  (`RefreshTokenStore`, `AccessTokenBlacklist`) — same mechanism as seller.

## Errors

Throw `CustomerException` (→ `CustomerErrorCode`, `CUSTOMER-0xx`) — never raw exceptions from services.
Notable: `INVALID_OAUTH_PROVIDER`, `INVALID_SIGNUP_SESSION` (social signup sid),
`INVALID_LOGIN_CODE` (temporary login code).

## Tests

`customer-core/src/test` — service tests (Mockito on ports), FixtureMonkey fixtures, fixed `Clock`.
Run: `./gradlew :modules:customer:customer-core:test`.

## Security

- **⚠️ Posture: allow-by-default (footgun).** The customer chain ends in `anyRequest().permitAll()`;
  only `CUSTOMER_PROTECTED_MATCHERS` (in `ApiSecurityConfig`: `me`, `me/nickname`, `logout`) require
  auth. **A new customer endpoint is PUBLIC unless added to `CUSTOMER_PROTECTED_MATCHERS`** — every new
  sensitive endpoint MUST be added there. Source of truth = `ApiSecurityConfig`.
- **Intentional:** `login` / refresh return the access token (body / `Authorization`) and the refresh
  token (cookie) — by design, **not** a token leak.
- Full attack surface: `.claude/agents/security-reviewer.md` (OAuth, token lifecycle, PII, authz).
