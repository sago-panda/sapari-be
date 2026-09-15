# product — catalog · options · pricing · Q&A

Buyer/seller product domain: category tree, options/combinations, period discounts, Q&A,
ranking/search-log, outbox. Internal layout + dependency rules: `modules/AGENTS.md` + root `AGENTS.md`.

## Status

- 🚧 **Seller product CRUD works** (epic SPR-56): create / detail / seller list / info update / option
  replace (+ surcharge·exclusion rules, per-combination overrides, SPR-108) / combination price·stock /
  sale status / soft delete, bulk create from xlsx (SPR-150, parsing lives in `apps/product-app/bulk`).
  Not yet: images (SPR-107), admin review, search indexing. Wired by `apps/product-app`.

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
- Repositories/mappers exist for every aggregate; services exist for the product/combination write paths above.

## Combination invariants (non-obvious — read before touching combinations)

- **Every product has ≥ 1 combination.** No option types ⇒ one hidden *default combination*
  (`combination_key = "DEFAULT"`, no option values, price = base_price). Stock/price/reservation of a
  single product live on it. ⚠️ This supersedes the order DDL comment "`option_combination_id` NULL =
  single product" — order/cart must reference the default combination's id (V1 migrations are applied, so
  the comment itself can't be edited).
- **Option replace retires, never deletes, combinations** (orders reference them by id):
  `discontinueAllByProductId` flips **all** of the product's combinations to `is_available=false` and bumps
  `version` (including ones the seller had already discontinued — so a stale "reactivate" PATCH gets 409),
  and renames a retired `DEFAULT` key to `DEFAULT#<id>` (the `(product_id, combination_key)` unique index is
  not partial). A retired combination can't be re-enabled (`UpdateCombinationPriceStockService` →
  PRODUCT-002). Combination keys are `:`-joined value UUIDs (the DDL comment's "3:7:12" is illustrative).
- **`min_price` / `has_stock` are written only by `ProductRepository.refreshStockPriceCache`** (entity columns
  are `updatable=false`; a normal product save can't overwrite them). Every path that changes a
  combination's price/stock/availability/reservation — or a winning discount — must call it **at the end of
  its transaction**. It flushes, `SELECT … FOR UPDATE`s the product row, then recomputes in a new statement
  (READ COMMITTED): lock order is **combination rows → product row**; it bumps `updated_at` (re-index
  trigger) but not `version`. Hot paths (order reservations) will serialize on that product row — revisit
  before wiring them.

## Conventions (when you build services)

- Immutable domain record + MapStruct entity↔record (`infrastructure/persistence/mapper`); time via
  `TimeProvider`; throw a `ProductException` (→ `ProductErrorCode`, `PRODUCT-0xx`) from services — never
  raw runtime exceptions. (See `modules/AGENTS.md` / root `AGENTS.md`.)

## Tests

Run: `./gradlew :modules:product:product-core:test` — service tests (Mockito on ports, fixed `Clock`),
repository tests on real Postgres via Testcontainers (`support/AbstractRepositoryIntegrationTest`, needs
Docker), and barrier-based concurrency tests (`concurrency/`, `support/ConcurrentTransactionTestSupport`).
