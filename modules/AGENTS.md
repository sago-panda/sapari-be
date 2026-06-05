# modules — domain module layout

Shared skeleton every domain module follows. Loaded when working under `modules/`.
Dependency rules (who may depend on whom) live in the root `AGENTS.md`; this file is the
**internal package layout** only.

## Every domain module = `X-api` / `X-core` pair

```
X-api/   command/   request DTOs / commands
         port/      use-case interfaces (inbound ports)
         view/      response DTOs                              ← no dependencies

X-core/  application/service/   *Service implements *UseCase   (the only @Transactional layer)
         application/port/       outbound ports + their result records
         domain/model/           domain records, sealed status, VOs
         domain/repository/      repository ports
         domain/exception/       domain exceptions + error-code enum
         infrastructure/<ext>/   adapters: persistence/ · redis/ · external-system/ · config/
```

- Inbound: controller → `X-api` `*UseCase` port → `X-core` `*Service`.
- Outbound: `*Service` → `application/port` interface → `infrastructure/<ext>` adapter (never call
  an SDK/JPA directly from a service).
- JPA entity ↔ domain record conversion lives in `infrastructure/persistence/mapper`.

`live` is the reference implementation — see `modules/live/AGENTS.md` for a worked example.

## Per-module `AGENTS.md` — what to write

Write it **after** the module works, from real code — not from a template. Keep it thin:
only what's **non-obvious and X-specific**. Don't restate this file or the root rules.

- **Always**: Status (done / WIP), test run command.
- **Add a section ONLY IF the module has one**: state machine (sealed status), external-system
  port, a caching/persistence quirk, or a domain invariant worth guarding.
- **Skip** anything a reader could infer from the code or that just repeats `modules/AGENTS.md`.

`modules/live/AGENTS.md` is the worked example — match its altitude, not its exact sections
(State Machine / Media exist because live needs them; your module may need none of them).
