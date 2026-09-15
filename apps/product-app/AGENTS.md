# product-app — product domain Spring Boot app (REST)

Standalone deployable for the `product` domain. Wires `product-core` + `common/web`. Domain logic +
schema live in `modules/product` — see `modules/product/AGENTS.md`.

## Status

- 🚧 **Seller product REST + public detail wired (③).** `controller/product/` =
  `SellerProductController` (`/api/v1/seller/products**`, ROLE_SELLER) + `ProductController`
  (`GET /api/v1/products/{id}`, public, ON_SALE-only gate). Request DTOs under
  `controller/product/dto/request` (`@Valid`, `toCommand(sellerId[, productId])`). Swagger UI at
  `/swagger-ui.html`, API docs at `/v3/api-docs`. Create/option-update accept option-value `ref` +
  `surchargeRules`/`exclusionRules`/`combinations` (⑤); structural rule checks are service-side (PRODUCT-004),
  DTOs only enforce scalar limits. Pending: image (④) fields.
- ✅ **Bulk upload (SPR-150)** `POST /api/v1/seller/products/bulk` (multipart `file`, xlsx ≤ 5MB, ≤ 100 products).
  Excel is an input format, so parsing lives here in `bulk/` (not core): streaming POI reader (temp file +
  SAX + row cap + zip entry cap) → `ProductWorkbookParser` builds the **same `CreateProductRequest`** so
  Bean Validation rules are reused, violations mapped back to sheet/row/column → core `BulkCreateProductsUseCase`
  (validates every product first; any error ⇒ nothing saved). Validation failures are a **200** envelope with
  `status=REJECTED` + cell errors (`ErrorResponse` has no room for a list); only an unreadable file is 400 PRODUCT-012.
  Template contract (sheet names/headers) = `bulk/ProductWorkbookTemplate`, fixture `src/test/resources/bulk/`.
  Multipart limits are set in code (`config/MultipartConfig`) because the yaml isn't committed.
- `@ComponentScan("com.sapari")` picks up `common/web`. JPA is split: `product-core`'s
  `JpaConfiguration` enables product persistence; this app's `config/UserPersistenceConfig` adds
  `com.sapari.user.infrastructure.persistence` (the JWT `UserDetailsService` needs it) — Spring Boot
  merges the two `@EntityScan` package sets.

## Runtime — must read before running

- **The app does NOT create the schema.** `ddl-auto: none` and there is **no Flyway in the app** —
  DDL is applied by the **infra runner** (`infra/migration/migrate.sh`, SQL at `db/migration/product/`;
  see `infra/AGENTS.md`). Boot against an un-migrated DB → entity mapping fails. Apply migrations first.
- DB: `jdbc:postgresql://localhost:5432/sapari` (user/pass `sapari`). Redis: `localhost:6379`.
- JWT config (`jwt.*`) is present (inherited via `common/web`). Security is wired in `config/SecurityConfig`
  (two stateless chains: seller `ROLE_SELLER` + public). Auth beans (`JwtTokenProvider`, revocation
  checkers, `UserDetailsService`) come from `common:auth` + `user-core` via component scan — so the app
  now needs **Redis + the user schema** too, not just product. Login/token issuance stays in api-app.
- `product-core`'s `Runner` (dev `ApplicationRunner`) runs a category query on **every startup** —
  remove it before relying on boot.

## Run

```bash
./gradlew :apps:product-app:bootRun        # needs Postgres + Redis up and product schema migrated
```
