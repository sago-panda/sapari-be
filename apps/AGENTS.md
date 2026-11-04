# apps — web layer conventions

Shared by all four apps (api-app · admin-app · streaming-app · batch-app).
App-specific detail lives in that app's own `AGENTS.md` (e.g. `apps/streaming-app/AGENTS.md`).
Who may depend on whom lives in the root `AGENTS.md` and is ArchUnit-enforced;
this file is **how the web layer uses** what it is allowed to depend on.
The reviewer judges the envelope rules below as `CONV-12` — change one, check the other
(`.claude/review/sapari-reviewer-methodology.md`). Paging assembly is `common/AGENTS.md` / `CONV-13`.

## Response envelope (`com.sapari.common.response`)

Every REST response has the same body shape. The envelope carries **body form only** —
the HTTP status is `@ResponseStatus`'s job.

```java
// 200
public ResponseEnvelope<GetLiveResult> getRooms() {
    return ResponseEnvelope.success(getLiveUseCase.getRooms(...));
}

// 201 — status via annotation, still just the envelope
@ResponseStatus(HttpStatus.CREATED)
public ResponseEnvelope<CreateLiveView> createRoom(...) { ... }

// 204 — no envelope
@ResponseStatus(HttpStatus.NO_CONTENT)
public void endBroadcast(...) { endLiveUseCase.end(...); }

// ResponseEntity ONLY when a header is required (Location, …)
public ResponseEntity<ResponseEnvelope<CreateLiveView>> createWithLocation(...) { ... }
```

- Return type is `ResponseEnvelope<T>` or `void`. A raw DTO with no envelope is a violation,
  and so is `ResponseEntity` used merely to set a status — that is what `@ResponseStatus` is for.
- **Failures are thrown, never assembled.** A service throws its domain exception
  (`BusinessException` subclass); `GlobalExceptionHandler` turns it into `fail(ErrorResponse)`.
  A controller that catches to build an error body — or calls `fail()` itself — is a violation.
- Never nest an envelope inside `data`.

```jsonc
{ "success": true,  "data": { /* … */ }, "error": null }
{ "success": false, "data": null,
  "error": { "status": 404, "code": "LIVE-002", "message": "…", "requestId": "…", "timestamp": "…" } }
```

`data`/`error` are always present (explicit `null`, never omitted) so clients see one fixed shape.

> Migration state: existing controllers still return `ResponseEntity<Dto>` without the envelope —
> success bodies are raw while errors are enveloped. New and changed controllers follow the rules
> above; the ArchUnit rule that would enforce the return type is deliberately **not** added until
> the sweep is done (it would fail the build on every existing controller).

Page types come back from the use-case port already assembled — the controller only wraps them:
`ResponseEnvelope<CursorPage<LiveListView>>`. How the page itself is built is `common/AGENTS.md`.

## Input validation — where a 400 comes from, and why the global handler stays narrow

`GlobalExceptionHandler` handles three things: `BusinessException` (its own status), `ConstraintViolationException`,
and a final `Exception` net that answers **500** and logs `Unhandled exception`. Everything else goes through
Spring's own handling.

Measured (2026-10-31), so don't re-derive it:

| Bad input | Status | Who answers |
|---|---|---|
| Malformed `UUID` in the path | **400** | Spring's binding |
| Non-numeric `@RequestParam int` | **400** | Spring's binding |
| `@Valid` DTO violation | **400** | handler / Spring |
| **`IllegalArgumentException` from a command record** | **500** + `Unhandled exception` | the final net |

That last row is the trap. A command `record`'s compact constructor is the right place for its invariant, but
reaching the web layer it reads as a server fault: a client bug pages someone, and the log says nothing was
handled.

**Two ways out, and both are per-endpoint on purpose:**

- Put the rule on a `@Valid` request DTO, which is the repo's usual idiom (`ChatModerationController`).
- Or translate at the controller boundary into a `BusinessException` with its own code — what
  `ChatHistoryController` does (`CHAT-009`), because the rule there is cross-field (two mutually exclusive
  query modes) and a class-level Bean Validation constraint would be more machinery than the translation.

**Do not add `@ExceptionHandler(IllegalArgumentException.class)` to the global handler.** It looks like the
structural fix and it is a regression: the same type arrives from infrastructure and from our own bugs, so a
blanket 400 would move **server faults off the 5xx alerting path**. A client error that 500s is noisy but
visible; a server bug that 400s is invisible. If a framework type genuinely needs mapping, add *that type*,
not its supertype.
