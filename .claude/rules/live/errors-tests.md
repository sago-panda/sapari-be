---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/exception/**"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/**"
  - "modules/live/live-core/src/test/**/*"
  - "apps/live-app/src/test/**/*"
---

# live — errors & tests

Moved from `modules/live/AGENTS.md` §Errors & tests (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

## Errors & tests

Every domain exception maps to a `LiveErrorCode`; no raw `IllegalState`/`RuntimeException` escapes a service.
A state violation gets **one code per request kind** (start LIVE-003, end/expire 009, enter 010, ingress 011, cancel 013, update 014) via
`InvalidLiveStateException(code, msg)` — keep the single class, reconcile jobs catch it as "lost the race, skip".
Seller input rejected by the domain (prices 008, pin count 012) is `InvalidLiveProductException`; room input
(schedule not future 015, thumbnail URL 016) is `InvalidLiveRoomException` — never IAE —
IAE reaches the global net as 500. A new request kind (cancel, edit, …) adds its own code; don't reuse 003.
Tests in `live-core/src/test` (Mockito on ports); scheduler wiring in `live-app` via `ApplicationContextRunner`.
FixtureMonkey for fixtures, plain builders when asserting specific values (random strings trip VO validation).
Run `./gradlew :modules:live:live-core:test`.
