# product-app HTTP 테스트 (IntelliJ HTTP Client)

product 도메인의 모든 REST API를 IntelliJ HTTP Client로 단건/연계(flow) 검증한다.
각 요청에 응답 핸들러(`> {% ... %}`)로 상태코드·본문 어설션 + 다음 요청용 값 캡처가 들어 있다.

## 파일

| 파일 | 내용 |
|---|---|
| `http-client.env.json` | 공개 환경값(URL·categoryId·onSaleProductId·sellerEmail) |
| `http-client.private.env.json` | 비밀값(sellerPassword·customerToken) — **gitignore 권장** |
| `00-auth.http` | 판매자 로그인 → `sellerToken` 전역 캡처 (api-app) |
| `01-seller-products.http` | 판매자 8엔드포인트 단건 happy-path + 어설션 |
| `02-public-product.http` | 공개 상세(GET) — ON_SALE 200 / 비공개·없음 404 |
| `03-flow-lifecycle.http` | **연계 flow**: 신규상품 라이프사이클 + ON_SALE 상품 조합/상태 flow |
| `04-validation-errors.http` | 음성 케이스 401/403/400/404/409 |

## 실행 전 준비 (필수)

1. **환경 선택**: IntelliJ 우상단 run configuration에서 `local` 환경 선택.
2. **포트 확인**: `productBaseUrl`(product-app)과 `apiAuthBaseUrl`(api-app, 판매자 로그인)은 **서로 다른 포트**일 수 있다. 두 앱의 실제 `server.port`(application.yml)에 맞춰 `http-client.env.json`을 수정.
3. **앱 기동**: product-app + api-app + Postgres + Redis 가동, product/user 스키마 마이그레이션 완료(`infra/migration`).
4. **판매자 계정**: `sellerEmail`/`sellerPassword`(private env)에 실재 판매자 계정. 없으면 api-app `POST /api/v1/sellers/auth/signup`으로 생성.
5. **categoryId**: `categoryId`는 **실재하는 카테고리 id**여야 한다(없으면 등록이 `PRODUCT-005`). DB의 `product_schema.categories`에서 확인.
6. **onSaleProductId**: 조합 수정/상태 전환/공개 상세 200 flow는 **ON_SALE 상태 상품**이 필요하다. 판매자 등록 직후 상태는 `PENDING_REVIEW`이고, 이를 `ON_SALE`로 올리는 경로는 **관리자 검수(미구현, ⑦)** 뿐이다. 따라서 DB에서 한 상품을 `ON_SALE`로 만들어(또는 관리자 승인 후) 그 UUID를 `onSaleProductId`에 넣는다.

## 실행 순서

`00-auth.http`(로그인) → `01`~`02`(단건) → `03`(flow) → `04`(에러). flow/단건은 전역변수(`sellerToken` 등)를 공유하므로 같은 IntelliJ 세션에서 위→아래로 실행한다.

## 알려진 제약 (설계상)

- **판매자 상세 엔드포인트 없음**: 현재 상세는 공개(`GET /api/v1/products/{id}`, ON_SALE 게이트)뿐이다. 판매자가 자기 `PENDING_REVIEW` 상품의 **버전·조합 id**를 보려면 목록(`GET /api/v1/seller/products`, 모든 상태·`version` 포함)을 쓴다. 단, 목록엔 조합 정보가 없어 **조합 수정은 ON_SALE 상품으로만** 검증 가능(공개 상세에서 조합 id·version 획득).
- `expectedVersion`은 수정 폼이 본 version. 신규 상품은 **목록**에서, ON_SALE 상품은 **공개 상세**에서 캡처한다.
- 응답 봉투: 성공은 View raw(`{...}`), 실패만 `{success:false, error:{status,code,message,...}}`.
</content>
