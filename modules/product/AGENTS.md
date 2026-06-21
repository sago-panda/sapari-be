# product — catalog · options · pricing · Q&A

Buyer/seller product domain: category tree, options/combinations, period discounts, Q&A,
ranking/search-log, outbox. Internal layout + dependency rules: `modules/AGENTS.md` + root `AGENTS.md`.

## Status

- 🚧 **Skeleton — pre-service.** Only JPA entities (catalog + option subset) and
  `CategoryJpaRepository` exist. **No domain model / service / port / `-api` yet** (`product-api` is
  empty). `Runner.java` is dev scratch (delete before real work).
- Build the layers (`application/service`, `domain/model`, `application/port`, `domain/exception`)
  following **`live`** (reference) and the auth modules (`customer`/`seller`) for the error/exception shape.

## Schema is the real asset

DDL — `db/migration/product/V1__init_product.sql` (schema `product_schema`), derived from the team
ERD. Applied by the **infra Flyway runner**, never Hibernate (`apps/product-app` runs `ddl-auto: none`).
Non-obvious facts that bite when you map entities:

- **No physical FK** anywhere (root rule). Cross-/intra-domain refs are bare `uuid`/`bigint` id
  columns; entities use `@ForeignKey(ConstraintMode.NO_CONSTRAINT)`.
- **`categories.id` is `bigint`** (used directly as the ltree `path` label) — **not uuid**. It is
  **`IDENTITY`** (DB-generated; entity = `LongTimeEntity`). The *other* `bigint`-PK tables
  (`product_search_logs`, `search_keyword_daily_stats`, `outbox_events`) are **app-supplied TSID, no
  `serial`** (batch-COPY compatibility) — not entities.
- **`product_images` is polymorphic**: owner = `product_id` **XOR** `option_value_id`, discriminated by
  `image_role` (GALLERY/DETAIL/BODY/SWATCH). One table — there is no separate detail/option-image table.
- **`product_option_combinations`** carries `reserved_stock` (`CHECK 0 <= reserved_stock <= stock`);
  the resolved discount lives 1:1 in `combination_winning_discounts` (derived cache, row-absent = no
  discount).
- **`product_option_configs`** (1:1 with products) holds option authoring rules as `jsonb`
  (`surcharge_rules` / `exclusion_rules`) — materialized into combinations at write time, not read at runtime.
- `products` sort/filter columns (`min_price`, `has_stock`, `purchase_count`, `avg_rating`,
  `*_count`) are **app-maintained denormalized caches**, not DB-computed.

## Entities (all 26 tables mapped, by DDD aggregate)

JPA entities for **every** `V1` table live in `infrastructure/persistence/entity/<aggregate>/`, split by
aggregate: `category`, `optionattribute` (global option templates, not per-product), `product`
(incl. `product/option/` — option types/values/config are **inside the Product aggregate**, edited with
the product), `productgroup`, `combination` (own aggregate — runtime stock/price, referenced by orders by
id), `stock`, `discount`, `faq`, `ranking`, `audit`, `search`, `outbox`. Conventions:

- **Bare id columns only** — no JPA relations/`@ManyToOne` (cross-/intra refs are `UUID`/`Long` fields,
  matching the no-physical-FK rule).
- **Timestamps via db-core bases — don't hand-roll**: `BaseUuidEntity` (uuid + created) /
  `UuidTimeEntity` (+ updated) / `LongTimeEntity` (bigint IDENTITY + created + updated, for `Category`).
- **Standalone (no base)** where none fits: natural-PK 1:1 tables (`ProductOptionConfig` keyed by
  `product_id`, `CombinationWinningDiscount` by `combination_id`) and the bigint **app-TSID** infra
  tables (`ProductSearchLog`, `SearchKeywordDailyStat`, `OutboxEvent` — `@Id Long` with no `@GeneratedValue`).
- **enum varchar** → `@Enumerated(STRING)` enum co-located with its aggregate. Exception: `outbox_events`
  `aggregate_type`/`event_type` stay `String` (values like lowercase `product` don't match enum names).
- **jsonb** (`metadata`, `surcharge/exclusion_rules`, `detail`, `payload`) → `String` +
  `@JdbcTypeCode(SqlTypes.JSON)`.
- No repositories/mappers/services yet — add per aggregate as you build.

## Conventions (when you build services)

- Immutable domain record + MapStruct entity↔record (`infrastructure/persistence/mapper`); time via
  `TimeProvider`; throw a `ProductException` (→ `ProductErrorCode`, `PRODUCT-0xx`) from services — never
  raw runtime exceptions. (See `modules/AGENTS.md` / root `AGENTS.md`.)

## Tests

None yet. Run: `./gradlew :modules:product:product-core:test` (service tests with Mockito on ports,
FixtureMonkey fixtures, fixed `Clock` via `TimeProvider` — match `customer`/`seller`).
