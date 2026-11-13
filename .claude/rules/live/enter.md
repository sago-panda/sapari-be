---
paths:
  - "apps/live-app/src/main/java/com/sapari/liveapp/security/{Enter*,TokenRevocationFilter,TokenBucket,StatelessJwtAuthenticationFilter}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{EnterRateLimitConfig,LiveSecurityConfig}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/EnterLiveService.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{CachingRoomTokenIssuer,RoomTokenProvider}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/RoomToken*.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{RoomToken*,EnterPopulation,EnterRejectionGate,TokenRevocationAxis}.java"
---

# live — enter rate limit & room token (detail)

Split from `modules/live/AGENTS.md` (SPR-166 / SPR-169 / SPR-173 sections, SPR-182). Facts stay in its
§Design-time invariants; this file only maps where the reasoning lives in code.

**Lives in code, not here:** signing cost (685µs/688µs, per-core throughput, webhook is not a comparison) →
`EnterRateLimiter`, `EnterRateLimitProperties` · member token cache (keys, half-TTL cap, guests excluded,
HMAC rejected, `live.token.signature`) → `CachingRoomTokenIssuer`, `RoomTokenCacheProperties` · two gates,
shard-then-global, burst headroom (12.0% → 0.0%), blast radius not fairness, shard key keyed per instance,
not per-IP, fixed-size array (≤4096), `gate` tag, throttled logs → `EnterRateLimiter`,
`EnterRateLimitProperties`, `EnterRejectionGate` · lowercase tag values → `PrometheusScrapeTest` · no
`@Transactional` on `enter`, pool of 10 → `EnterLiveService` · the limit does not throw, a controller must
not assemble it → `EnterRateLimitFilter` · instance-local buckets, rolling-restart recovery, cap is on
issuance not WS sessions → `application.yaml.example`, `EnterRateLimitConfig` · revocation `jti`/`sid`,
filter order, confirmed revocation → guest, failed lookup → 503, two round trips → `TokenRevocationFilter`,
`LiveFilterOrderTest`, `RevocationOnPublicRouteTest` · scan exclusion → `LiveAppApplication` · never compare
per-core supply to fleet demand (no core count recorded here), invalid limits refuse to boot →
`EnterRateLimitProperties` · a rejected call pays neither `findById` nor the signature →
`EnterRateLimitFilter` · why revocations and lookup failures are both counted → `LiveMetrics` · CPU ceiling,
seller lane (SPR-185) → `EnterRateLimiter` · seller multi-account risk accepted on duplicate-business-number
rejection, revisit if relaxed → `EnterRateLimitProperties` (seller defaults).
