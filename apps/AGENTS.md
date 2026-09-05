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
