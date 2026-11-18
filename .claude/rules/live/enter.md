---
paths:
  - "apps/live-app/src/main/java/com/sapari/liveapp/security/{Enter*,TokenRevocationFilter,TokenBucket,StatelessJwtAuthenticationFilter}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/{EnterRateLimitConfig,LiveSecurityConfig}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/EnterLiveService.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/media/{CachingRoomTokenIssuer,RoomTokenProvider}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/config/RoomToken*.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/{RoomToken*,EnterPopulation,EnterRejectionGate,TokenRevocationAxis}.java"
  - "apps/live-app/src/main/resources/application*.yaml*"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{Enter,TokenRevocation,TokenBucket,LiveFilterOrder,RevocationOnPublicRoute,CachingRoomTokenIssuer,RoomToken}*Test.java"
---

# live — enter rate limit & room token (detail)

Split from `modules/live/AGENTS.md` (SPR-166 / SPR-169 / SPR-173 sections, SPR-182). Design-time facts stay in its
§Design-time invariants; §Room token moved here too (SPR-154, section below).

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

## Room token — chat entry auth

`enter` issues an RS256 token; distinct key/alg/`aud` from the api-app HMAC token, never interchangeable.
Unauth viewers get an ephemeral GUEST token. Claims: `role`, `owner` (chat's PII gate), ~90s `exp` — capped (refuses to boot above it; the cap is a §Design-time invariant).
The room-token cache and revocation (503 vs. guest downgrade, filter order) → the class map above.
