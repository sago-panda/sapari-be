# sapari-be — agent guide

Live commerce backend. Spring Boot 4 / Java 21, Gradle multi-module backend.
Domain modules are shared across separately deployed apps: api-app, admin-app, streaming-app, batch-app.

## Tech Stack

Non-obvious / decision-bearing only; defaults (Spring Boot, Lombok, JUnit) → `build.gradle`.

| Area | Choice |
|---|---|
| Runtime | Java 21 · **Spring Boot 4.0.6** (Gradle, Groovy DSL) |
| Persistence | PostgreSQL (JPA/Hibernate) · **QueryDSL** · Redis · **MapStruct** (entity ↔ domain record) |
| Media | **LiveKit** SFU + HLS Egress → S3 |
| Test fixtures | **FixtureMonkey** |

## Codebase Map

```
apps/      separate Spring Boot apps / Pods: api-app (REST) · admin-app · streaming-app (WS/SFU) · batch-app (settlement)
common/    domain-agnostic shared code (core / web / global)
storage/   persistence adapters (db-core / redis-core / search-core / object-storage)
modules/   domain modules — each an -api / -core pair (internal layout: modules/AGENTS.md)
           live ✅ broadcast (chat WIP) · member, seller, product, order, promotion, notification ⚠️ skeleton only — follow live
```

Module-specific conventions: **prefer the `AGENTS.md` inside that directory**.

## Module Dependency Rules (MUST NOT VIOLATE)

```
apps/*            → modules/*-api, modules/*-core, common/*, storage/*
modules/X/X-core  → modules/X/X-api, common/*, storage/*
                  → modules/Y/Y-api ONLY (never another domain's -core)
modules/X/X-api   → no internal module dependencies; DTO / interface only
```

- Cross-domain calls go through `*-api` ports OR **domain events** — never direct, never another domain's `-core`.
- `-core` is never depended on by other modules (Gradle `implementation` scope).
- `*-api` may depend on the shared foundation **only via `com.sapari.global.page`** (CursorPage/OffsetPage in use-case return types); the rest of `common/global` (TimeProvider, …) must NOT leak into `-api`.
- **`com.sapari.common.response`** (ResponseEnvelope/ErrorResponse) is **controller/exception-handler only** — domain `-core` and `-api` must NOT depend on it (the envelope is wrapped at the web layer, not returned by use-case ports).

## Architectural Decisions (affect how you write code)

- **Domain model = immutable record.** Use sealed status/state types when domain transitions need explicit modeling. Transitions return new instances; never `@Entity` or mutable fields on a domain model.
- **JPA entity ↔ domain record separated**, converted via MapStruct in `infrastructure/persistence/mapper`.
- **External systems (SFU, S3, …) isolated behind ports.** e.g. `LiveMediaManager` ← `LiveKitMediaManager`.
- **Validation**: VOs in compact constructors; aggregates in static factories (`create()`).
- **Time via `TimeProvider`** — never `Instant.now()` directly in services.
- **Async cross-domain fan-out = domain events over Redis Pub/Sub** (broadcast start/end). Runtime/deploy: `infra/AGENTS.md`.
- **Minimize external calls inside a transaction**, and **never swallow exceptions** (`catch (Exception e) { log... }`) — re-throw, or translate known infrastructure exceptions into domain/application exceptions.
- **Never commit** `.env`, secrets, or credentials.

## Common Commands

```bash
./gradlew :apps:api-app:bootRun            # run an app
./gradlew :modules:live:live-core:test     # test one module
./gradlew build -x test                    # full build, skip tests
./gradlew test                             # full test
```

## Behavioral Guide

All code work follows **[.claude/rules/karpathy-guidelines.md](./.claude/rules/karpathy-guidelines.md)** — scope, simplicity, surgical diffs, goal-driven execution.

## Task-Based References (JIT index)

Create a missing referenced file only when starting work in that area.

| Task | Reference |
|---|---|
| Live broadcast domain | `modules/live/AGENTS.md` |
| New domain module layout | `modules/AGENTS.md` |
| Order / payment domain | `modules/order/AGENTS.md` |
| JPA entities | `storage/db-core/AGENTS.md` |
| Redis cache patterns | `storage/redis-core/AGENTS.md` |
| WebSocket / STOMP | `apps/streaming-app/AGENTS.md` |
| LiveKit integration | `.claude/skills/livekit-integration.md` |
| Infra / deploy / CI-CD | `infra/AGENTS.md` |

_Keep under 100 lines. Push domain/infra detail to the sub-`AGENTS.md` files in the index above._
