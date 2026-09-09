# live 도메인 체크리스트 (`LIVE-*`)

`domain-reviewer` 가 `domain=live` 로 읽는다. 각 항목은 `modules/live/AGENTS.md` 의 절을 근거로 하며,
그 파일이 바뀌면 여기도 같이 본다 — 근거가 사라진 항목은 `CONV-10` 감이다.

---

- `LIVE-01` **State machine stays closed** (§State machine) — a new `LiveStatus` variant lands with
  `sealed permits` + every `LiveRoomMapper` switch + the `LiveRoomStatus` enum **together**; a transition
  added without its guard method; a transition *into* `Suspended` appearing without the exit path being
  reconsidered; `expire()` starting to publish `RoomEnded` (it must not — the room was never `Live`).
- `LIVE-02` **Media port failure direction** (§Media ports) — a new or changed port call must pick a
  documented side: cleanup (`deleteIngress`, `stopHlsEgress`, `closeRoom`) swallows; global sweeps
  (`listAll*`) throw and treat **null body = failure**; per-room `publishingIngressIdsOrEmpty` returns empty
  and treats **null body = empty**. A call that can destroy a broadcast failing quiet, or one that only
  withholds a promotion failing loud, is the finding. **Do not flag** start-side media inside
  `@Transactional` or end-side cleanup after commit — both are documented intentional.
- `LIVE-03` **RTMP go-live reaches one outcome from both orders** (§RTMP) — seller `arm` and OBS
  `ingress_started` may land in either order; whichever is second must promote, and both must require
  the room to **acknowledge the ingress** (`LiveRoom.hasIngress`). Promoting on an unacknowledged ingress,
  or a new `LiveStatus` variant that the `assignRtmpIngressIfAbsent` `WHERE` guard does not know, is the
  finding. End cleanup must delete ingress room-wide **before** `closeRoom`.
- `LIVE-04` **streamKey and room token are credentials** (§RTMP, §Room token) — streamKey never stored,
  never logged, returned once; the RS256 room token never logged raw (its claims carry email). A new
  DTO, log line or cache that carries either is the finding.
- `LIVE-05` **Reconciliation decides per room, on DB status, right before acting** (§Orphan
  reconciliation) — a once-per-round snapshot; staleness from `updated_at` or viewer count instead of
  `started_at`; gating a room sweep on participant count; **dropping the "global zero is not evidence"
  abort** in `end-stale-live`; adding grace to the `Ended` room/ingress sweeps (the file explains why
  grace there opens the hole it would seem to close); deleting where "no DB row → log only" applies.
  Threshold/cron/batch-size changes: is the stagger (`0/10`, `3/10`, `6/10`) kept, and does the new
  blast radius stay reversible?
- `LIVE-06` **Webhook handlers are idempotent and dedup'd before they destroy** (§LiveKit webhooks) — a
  handler that ends/deletes/charges ships with event-id dedup **in the same change**; exceptions are
  logged and 200 is still returned (a throw does not trigger re-send); the `createdAt` window stays
  asymmetric (past 15m / future 60s); the rate limit is not lowered toward real traffic (it is a CPU
  ceiling — lowering it starves `ingress_started` cheaply).
- `LIVE-07` **State-transition reads lock the row; ownership stays in the query** (§Persistence) — a
  transition path reading with `findById` instead of `findByIdForUpdate` /
  `findByIdAndSellerIdForUpdate`; an ownership check moved from the query into the service (leaks
  room existence); `LiveRoomMapper.updateEntityFromDomain` no longer writing `scheduledAt` out of the
  status (a re-save wipes it). Hibernate emits `for no key update` — that is the lock, not its absence.

Catch-all: none. A live-specific problem no item covers is reported under the closest item with a note
that the checklist is missing it.
