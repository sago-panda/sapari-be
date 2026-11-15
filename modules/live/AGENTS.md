# live — broadcast domain

Reference implementation for a new domain. Root `AGENTS.md` owns the cross-cutting rules.
✅ create / start (WebRTC·RTMP) / enter / end / cancel·update / lists / replay / reconciliation. 🚧 chat.

**This file is a signpost.** It keeps only the invariants a spec or plan must respect, and where the rest lives.
Area rules (what and why) are in `.claude/rules/live/`. Claude Code loads each only when a matching file is
touched, so **anyone planning, writing a spec, or not using Claude Code: open the file for every area the work
touches — if unsure, all of them.**
How, and why that belongs to one class, is in that class's comment.

| Area | Rules file (`.claude/rules/live/`) |
|---|---|
| State machine (`LiveStatus`), cancel/update, adding a state | `state-machine.md` |
| Media ports — failure direction; RTMP go-live, streamKey | `media.md` |
| HLS egress artifacts & replay (`LIVE-08`) | `replay-egress.md` |
| Room thumbnail upload | `thumbnail.md` |
| LiveKit webhooks | `webhooks.md` |
| Persistence & cache, live/upcoming lists, recent views | `persistence.md` |
| Enter rate limit & room token | `enter.md` |
| Orphan reconciliation | `reconcile.md` |
| Observability (`LiveMetrics`) | `observability.md` |
| Error codes & tests | `errors-tests.md` |
| Contracts other domains consume (live-api, room-end signal, room-token claims) | `contracts.md` |

## Design-time invariants (check before spec/plan)

Path-scoped rules load only when a matching file is touched, so a spec or plan never sees them. Each fact
here is written once; the named class holds the reasoning.

- **Replay is public** — no signature, ownership check or separate prefix — and served by its own use case, never
  the authorization-only `GetLiveRoomUseCase` view (`GetLiveReplayUseCase`).
- **`MasterPlaylistPublisher` runs under the start row lock and is not bounded by `callTimeout`**; an adapter must
  cap the whole call, retries included, at 15s (`MasterPlaylistPublisher`).
- **ABR replay needs both masters published, or both URLs fall back to 720p** (`LiveKitMediaManager.publishMaster`).
- **`playlist_name` and `live_playlist_name` must differ** — LiveKit rejects the request, so every start fails (`LiveKitMediaManager`).
- **`Ended` is a DB state, not egress completion** — the last segments and ENDLIST may still be finalizing; S3
  retention must keep the EVENT playlist as long as its segments (`infra/AGENTS.md`).
- **Replay is shown only at `vod_status = READY`; only two conditional UPDATEs write it — never the entity**
  (`EgressWebhookHandler`, `RecoverStuckReplaysService`; detail: replay-egress rules).
- **Legacy archive rows stay** — remove the legacy-hiding mapping (`LiveRoomMapper.archiveUrl`) only after a verified
  backfill (incl. rows ended by old replicas) and full retirement of pre-SPR-148 writers; never auto-replace
  filenames or invent replay URLs for pre-upgrade rooms.
- **Before a bulk UPDATE of `live_room.status`, set `LIVE_RECONCILE_ORPHAN_MEDIA_ENABLED=false` and rolling-restart
  first** — it gates bean registration, so it neither applies without a restart nor stops a running round.
- **A round's `aborted` and `failed` outcomes never merge** (`live.reconcile.round{outcome}`; why: `LiveMetrics`).
- **Any role-demotion path must revoke tokens** (`JwtTokenLifecycle.revokeSession`/`revokeAllSessions`). None
  exists today — a role is fixed at creation (`UserEntity`).
- **Revocation gates issuance only** — an issued room token lives until `exp`, and its WS session as long as
  `ChatSessionRegistry` allows.
- **Inside the cache TTL a member gets the same room token; there is no `jti`** — the exposure window is unchanged,
  since the token was already replayable and the chat session cap (`chat.session.max-per-room`) is per user.
- **The enter cap is a CPU ceiling** — supply is per core, demand is fleet-wide; never compare them without a core count (`EnterRateLimitProperties`).
- **The seller enter lane's multi-account risk is accepted only because seller signup rejects duplicate business numbers** (`SellerAuthService`; why: `EnterRateLimitProperties`).
- **Accepted: one room can outlive its reconcile lease → duplicate cleanup** (`LiveReconcileProperties.roundBudget`).
- **A new `status` value is a rollout constraint** — an old replica cannot read it and fails whole rounds; expose
  the transition that writes it only after every replica runs the new code (`LiveRoomStatus`; detail: state-machine rules).
- **Terminal = `Ended` or `Cancelled`** — "finished" checks use `isTerminal()`; replay alone is `Ended`-only (`LiveStatus`).
- **Every exit from `Live` must remove the room from the Redis live list** (`LiveListCacheSync`).
- **live-core never depends on chat-api** — viewer counts come through `LiveViewerCounter`, wired in live-app.
- **streamKey is a credential** — never stored, never logged, returned once. **The room token carries email (PII)** —
  never log it raw; it is **not single-use** and its `exp` is capped at 300s, which chat sizes its TTLs on (`RoomTokenProperties`).
- **Each request kind gets its own `LIVE-xxx` code; never `IllegalArgumentException`** — it reaches the global
  handler as 500 (`LiveErrorCode`).
- ⚠️ **Recent-view retention is unresolved (follow-up)** — per-member viewing history is never deleted.

Run `./gradlew :modules:live:live-core:test`.
