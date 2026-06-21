# product-app — product domain Spring Boot app (REST)

Standalone deployable for the `product` domain. Wires `product-core` + `common/web`. Domain logic +
schema live in `modules/product` — see `modules/product/AGENTS.md`.

## Status

- 🚧 **Boots, no endpoints.** No controllers yet (`product-api` is empty); only the boot class +
  `SwaggerConfig`. Swagger UI at `/swagger-ui.html`, API docs at `/v3/api-docs`.
- `@ComponentScan("com.sapari")` picks up `common/web`; JPA is enabled by `product-core`'s
  `JpaConfiguration` (`@EntityScan`/`@EnableJpaRepositories` on `com.sapari.product.infrastructure.persistence.*`).

## Runtime — must read before running

- **The app does NOT create the schema.** `ddl-auto: none` and there is **no Flyway in the app** —
  DDL is applied by the **infra runner** (`infra/migration/migrate.sh`, SQL at `db/migration/product/`;
  see `infra/AGENTS.md`). Boot against an un-migrated DB → entity mapping fails. Apply migrations first.
- DB: `jdbc:postgresql://localhost:5432/sapari` (user/pass `sapari`). Redis: `localhost:6379`.
- JWT config (`jwt.*`) is present (inherited via `common/web`) but **no security chain / protected
  endpoints are wired yet** — add them with the first controllers.
- `product-core`'s `Runner` (dev `ApplicationRunner`) runs a category query on **every startup** —
  remove it before relying on boot.

## Run

```bash
./gradlew :apps:product-app:bootRun        # needs Postgres + Redis up and product schema migrated
```
