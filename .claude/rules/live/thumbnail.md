---
paths:
  - "modules/live/live-core/src/main/java/com/sapari/live/domain/model/{ThumbnailUrlRule,ThumbnailKey}.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/infrastructure/storage/**"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/port/ThumbnailUploadSigner.java"
  - "modules/live/live-core/src/main/java/com/sapari/live/application/service/IssueThumbnailUploadService.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/LiveThumbnailController.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/controller/live/dto/constraint/LiveThumbnailUrl.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/security/{CredentialIssue*,ThumbnailUploadRateLimitProperties}.java"
  - "apps/live-app/src/main/java/com/sapari/liveapp/config/CredentialIssueRateLimitConfig.java"
  - "{modules/live/live-core,apps/live-app}/src/test/java/**/{ThumbnailUrlRule,ThumbnailKey,S3PostPolicySigner,IssueThumbnailUploadService,LiveThumbnail}*Test.java"
  - "apps/live-app/src/test/java/**/CredentialIssueRateLimit*.java"
---

# live — room thumbnail

Moved from `modules/live/AGENTS.md` §Room thumbnail (SPR-154), which is now a signpost plus the design-time
invariants. What and why only; how lives in the named class's comment.

## Room thumbnail — presigned POST, server signs only

Seller asks `POST /api/v1/lives/thumbnails/uploads` → the browser POSTs the file straight to S3; bytes never pass
the app. Content (magic bytes) is checked **after** upload by an infra Lambda (separate ticket) that moves
`uploads/thumbnails/…` → `thumbnails/…` by **prefix swap only**, so the final URL is known at issue time. Until it
ships, `thumbnails/` stays empty and issued URLs 404 even though room create accepts them.

- LIVE-016 accepts only the requester's own CDN thumbnail path and never asks S3 whether it exists; update checks
  it only when the URL changes (`ThumbnailUrlRule`).
- **The signer supports static keys only** — moving to IRSA means replacing it. **Never log the policy, signature
  or keys** (`S3PostPolicySigner`).

## 발급 상한 — 막는 것은 서명 CPU 가 아니라 S3 업로드 용량 (SPR-207)

허가 하나가 `uploads/thumbnails/` 에 객체 하나를 올릴 권리이고 발급은 요청당 한 번이라, 발급을 세지 않으면
판매자 한 명이 버킷에 올릴 수 있는 양에 천장이 없다 — 검증 Lambda 가 들어오면 그 호출 수까지 같이 는다.

- **판매자 샤드 → 전역** 두 관문이고, 게스트 칸은 없다 — 이 경로는 SELLER 바닥에 걸려 미인증 요청이 비싼 일에
  닿지 않으므로, 신원 없는 요청은 세지 않고 넘긴다(`CredentialIssueRateLimitFilter`).
- 상한은 **파드 로컬**이고 버킷은 생성자에서 조립된다 — **한도 변경은 롤링 재시작이다**
  (`ThumbnailUploadRateLimitProperties`).
- **서명 키는 egress 와 다른 전용 IAM 사용자다** — `THUMBNAIL_S3_ACCESS_KEY`/`_SECRET_KEY`,
  권한은 `uploads/thumbnails/*` 의 `PutObject` 하나뿐이다(`ThumbnailProperties`). presigned POST 는
  서명자 권한을 물려받으므로 egress 키(`S3_*`, `live/` 전용)를 넣으면 `AccessDenied` 로 끝난다.
  인프라는 live-infra main 에 있다(SSM `/sapari/dev/media/thumbnail/*`).
- ⚠️ **검증 Lambda 가 아직 없어서 업로드만으로는 썸네일이 보이지 않는다.** `uploads/thumbnails/` 는 1일 뒤
  만료되고 `thumbnails/` 로 옮기는 주체가 없으므로, 발급·업로드가 성공해도 `ThumbnailUrlRule` 이 요구하는
  주소는 404 다. 둘은 별개 — 상한이 막는 것은 업로드 용량이고, 이것은 노출 경로가 비어 있는 것이다.

