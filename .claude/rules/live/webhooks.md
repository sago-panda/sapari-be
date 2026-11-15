---
paths:
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/webhook/**"
  - "apps/live-app/src/main/java/com/sapari/liveapp/security/Webhook*.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/WebhookRateLimitConfig.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{LiveWebhookEvent,LiveWebhookHandler,WebhookVerifier}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/{LiveWebhookService,*WebhookHandler}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/LiveKitWebhookVerifier.java"
  - "apps/live-app/src/main/resources/application*.yaml*"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{LiveKitWebhook,LiveWebhookService,IngressStartedWebhookHandler,EgressWebhookHandler,Webhook}*Test.java"
---

# live — LiveKit webhooks

Moved from `modules/live/AGENTS.md` §LiveKit webhooks (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

## LiveKit webhooks

`POST /webhooks/livekit`, `permitAll` — auth is the **body signature**, not Spring Security. Body is read
through a bounded stream, not `@RequestBody byte[]`.

- **Signature alone does not stop replay**; a `createdAt` window gates it — sizes and why: `LiveKitWebhookVerifier`.
- **A destructive handler (`egress_ended`, `room_finished`, …) requires event-id dedup in the same change.**
  `ingress_started` is a no-op unless the room is `Ready+RTMP`, and that is what caps replay damage; the egress
  handler is non-destructive — it only moves `vod_status` forward (`EgressWebhookHandler`), so it needs no dedup.
- Handlers belong to each feature, not the webhook package. **Must be idempotent.** Exceptions are logged and
  still return 200 — a throw does not trigger re-send.
- The rate limit is a **CPU ceiling, not availability protection** — nothing tells a forged request from a
  real one before verifying it. Keep it far above real traffic; lowering it lets an attacker starve
  `ingress_started` cheaply. rps limiting belongs upstream. Counter is per-instance.
  **Accepted: a dropped `ingress_started` is recovered only by `expire-ready`'s threshold**, so the room sits in
  `Ready` until then. Whether LiveKit re-sends after a 429 is not visible from this repo.
