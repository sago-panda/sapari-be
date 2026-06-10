# sapari

라이브 커머스 백엔드 — 모듈러 모놀리식 구조 (Spring Boot 4 / Java 21)

## 목차

- [패키지 구조](#패키지-구조)
- [레이어 역할](#레이어-역할)
- [모듈 의존 규칙](#모듈-의존-규칙)
- [DB 스키마 관리 (Flyway)](#db-스키마-관리-flyway)
- [브랜치 컨벤션](#브랜치-컨벤션)
- [커밋 컨벤션](#커밋-컨벤션)
- [MR 컨벤션](#mr-컨벤션)
- [머지 전략](#머지-전략)

---

## 패키지 구조

```
sapari-be/
├── settings.gradle                # Groovy DSL. apps/common/storage/modules 하위 디렉토리 자동 include
├── build.gradle
│
├── apps/                          # 실행 가능한 애플리케이션 (entry points)
│   ├── api-app/                   # 사용자용 REST API
│   ├── admin-app/                 # 어드민
│   ├── streaming-app/             # 라이브 송출/시청 (WebSocket, SFU 연동)
│   └── batch-app/                 # 정산/통계 배치
│
├── common/                        # 공용 기술 컴포넌트 (상세: docs/package-structure.md §6)
│   ├── core/                      # 순수 계약 — BusinessException, ErrorCode
│   ├── global/                    # 횡단 유틸 — TimeProvider 등
│   ├── page/                      # 페이지네이션 토대 — CursorPage/OffsetPage, CursorCodec
│   ├── web/                       # 웹 공통 — 예외 핸들러, 필터, 웹 설정
│   ├── security-jwt/              # JWT 발급·검증 + 토큰 스토어 포트
│   └── auth/                      # 인증 인프라 구현 — 토큰 스토어 Redis 구현
│
├── storage/                       # 영속성 어댑터
│   ├── db-core/                   # JPA/QueryDSL 공통 (BaseEntity, 감사 설정)
│   ├── redis-core/
│   ├── search-core/               # OpenSearch/ES (예정)
│   └── object-storage/            # S3 (예정)
│
├── db/migration/                  # Flyway 마이그레이션 SQL (도메인 스키마별 — 아래 섹션 참고)
├── infra/migration/               # 마이그레이션 러너/이미지
│
└── modules/                       # 도메인 모듈 — 각 X-api/X-core 쌍 (상세: docs/package-structure.md)
    ├── user/                      # 공통 신원·계정 (customer·seller가 user-api로 사용) ✅
    ├── customer/                  # 구매자 — 소셜 로그인 플로우 ✅
    ├── seller/                    # 판매자 — 이메일+비밀번호 플로우 ✅
    ├── live/                      # 라이브 방송 + 채팅 ✅ (레퍼런스 구현)
    ├── product/                   # 상품 + 카탈로그 + 리뷰 (스켈레톤)
    ├── order/                     # 장바구니 + 주문 + 결제 + 배송 (스켈레톤)
    ├── promotion/                 # 쿠폰 + 포인트 (스켈레톤)
    └── notification/              # 알림 (스켈레톤)
```

> 모듈 내부 패키지 배치(`X-api`의 command/port/view, `X-core`의 domain/application/infrastructure)는
> **[docs/package-structure.md](docs/package-structure.md)** 가 기준 문서입니다 (ArchUnit으로 빌드에서 강제).

## 레이어 역할

| 레이어 | 역할 |
|---|---|
| `apps/*` | 실행 가능한 애플리케이션. Controller와 진입 설정만 포함 |
| `common/*` | 도메인과 무관한 공용 기술 코드 |
| `storage/*` | DB/Redis/검색/스토리지 공통 설정 |
| `modules/*/​*-api` | 외부 모듈에 공개되는 계약 (DTO, 이벤트, UseCase 포트 인터페이스) |
| `modules/*/​*-core` | 도메인 + 애플리케이션 + 인프라 구현 (외부 비공개) |

## 모듈 의존 규칙

```
apps/*           → modules/*/​*-api,  modules/*/​*-core,  common/*,  storage/*

modules/X/X-core → modules/X/X-api,  common/*,  storage/*
                 → modules/Y/Y-api  (다른 도메인은 api만 의존)

modules/X/X-api  → (내부 모듈 의존 없음 — DTO/인터페이스만. 공유 토대는 common/page만 허용)
```

핵심 원칙:

- 도메인 간 직접 호출은 `*-api` (port 인터페이스) 통해서만
- 트랜잭션을 가로지르는 협력은 도메인 이벤트로 처리
- `*-core`는 외부 모듈이 절대 의존하지 않음 (Gradle `implementation`)

---

## DB 스키마 관리 (Flyway)

스키마는 더 이상 `ddl-auto: update`가 만들지 않습니다. **Flyway 마이그레이션 SQL이 스키마의
source of truth**이고, 앱은 `ddl-auto: validate`로 엔티티 ↔ DB 일치 **검증만** 합니다.

```
db/migration/<도메인>/V*.sql     ← 스키마 정의 (도메인 = PostgreSQL 스키마, 모듈과 1:1)
infra/migration/migrate.sh       ← 스키마별 독립 Flyway 실행 (각자 history·버전)
infra/migration/Dockerfile       ← 마이그레이션 이미지 (운영에선 배포 Job이 실행)
```

### ⚠️ 현재 상태 — 엔티티에 스키마 미적용 (과도기)

마이그레이션 SQL은 도메인별 스키마(`user_schema.users`, `live_schema.live_sessions` …)에
테이블을 만들지만, **현재 엔티티에는 아직 `@Table(schema=...)`가 붙어 있지 않습니다.**
엔티티는 기존처럼 `public` 스키마의 테이블(`users`, `live_rooms` …)을 바라봅니다.

- **지금 개발할 때**: 기존 방식 그대로 부팅·개발하면 됩니다. 로컬 DB의 기존 public 테이블을
  사용하며, 마이그레이션을 돌릴 필요 없습니다.
- **별도 브랜치에서 진행 중**: 엔티티 `@Table(schema=...)` 적용 + live 도메인 DDL 정렬
  (`live_rooms` → `live_sessions`, 미디어/상품 모델 변경).
- **그 브랜치가 머지되는 시점(컷오버)**: 로컬 DB의 기존 public 테이블을 drop하고
  마이그레이션을 1회 실행해야 합니다. 머지 시 공지 예정 —
  그 전까지는 아무것도 바꿀 필요 없습니다.

### 마이그레이션 실행 (스키마 작업자/컷오버 시에만)

```bash
docker build -f infra/migration/Dockerfile -t sapari-migration:dev .
docker run --rm \
  -e DB_URL="jdbc:postgresql://host.docker.internal:5432/sapari_db" \
  -e DB_USER=postgres -e DB_PASSWORD=<비밀번호> \
  sapari-migration:dev
```
반드시 **primary**에만 실행합니다(replica는 WAL로 자동 전파). 앱이 마이그레이션을 실행하는
일은 없습니다(`spring.flyway` 설정 금지).

### 마이그레이션 작성 규칙

| 규칙 | 내용 |
|---|---|
| 파일명 | 최초: `V1__init_<스키마>.sql` / 이후: `V<yyyyMMddHHmm>__<동사>_<대상>.sql` (예: `V202607021430__add_social_accounts.sql`) |
| 버전 충돌 | 스키마별 독립 시퀀스 + 타임스탬프라 브랜치 간 충돌 없음 (`outOfOrder` 적용됨) |
| dev 단계 | 운영 배포 전까지 **V1 직접 수정 + DB drop·재마이그레이션 허용** (운영 후엔 증분 V만) |
| 하위호환 | 운영 후엔 한 릴리스에 `ADD COLUMN(nullable/default)`·`CREATE TABLE`·`CREATE INDEX`만. **DROP/RENAME/NOT NULL은 expand → migrate → contract로 릴리스 분리** (구버전 파드 공존 보호) |
| 새 도메인 추가 | `db/migration/<도메인>/V1__init_<도메인>.sql` 생성 + `infra/migration/migrate.sh`의 `SCHEMAS`에 한 줄 추가 + 엔티티에 `@Table(schema="<도메인>_schema")` |
| FK | 도메인(스키마) 간 물리 FK 금지 — uuid id 참조만 (모듈 의존 규칙과 동일 원칙) |

---

## 브랜치 컨벤션

### 형식

```
<type>/<JIRA-KEY>
```

### Type

| Type | 용도 | 예시                                    |
|---|---|---------------------------------------|
| `feature` | 신규 기능 | `feature/SPR-123` |
| `fix` | 일반 버그 수정 | `fix/SPR-145`    |
| `hotfix` | 운영 긴급 패치 | `hotfix/SPR-201`   |
| `refactor` | 동작 변경 없는 리팩토링 | `refactor/SPR-167`    |
| `perf` | 성능 개선 (동작 변경 없음) | `perf/SPR-210`     |
| `chore` | 빌드·설정·의존성 | `chore/SPR-180`       |
| `docs` | 문서만 변경 | `docs/SPR-190`              |
| `test` | 테스트만 추가/수정 | `test/SPR-155`       |

### 보호 브랜치

```
main      ← 운영 배포 (직접 push 금지, MR만)
dev   ← 통합 브랜치 (개발 서버 자동 배포)
*         ← 위 컨벤션 따라 생성, dev로 MR
```

---

## 커밋 컨벤션

### 형식 (Conventional Commits + Jira)

```
<type>(<scope>): <subject> [JIRA-KEY]

<body (선택)>

<footer (선택)>
```

### Type

| Type | 의미 |
|---|---|
| `feat` | 신규 기능 |
| `fix` | 버그 수정 |
| `refactor` | 리팩토링 (동작 변경 없음) |
| `perf` | 성능 개선 |
| `test` | 테스트 추가/수정 |
| `docs` | 문서 |
| `chore` | 빌드·설정·의존성 |
| `style` | 포맷·세미콜론 등 (로직 변경 X) |
| `ci` | CI 파이프라인 |
| `revert` | 이전 커밋 되돌림 |

### Scope (모듈 구조 기준)

| Scope | 대상 |
|---|---|
| `user` | `modules/user/**` |
| `customer` | `modules/customer/**` |
| `seller` | `modules/seller/**` |
| `product` | `modules/product/**` |
| `live` | `modules/live/**` |
| `order` | `modules/order/**` |
| `promotion` | `modules/promotion/**` |
| `notification` | `modules/notification/**` |
| `common` | `common/**` |
| `storage` | `storage/**` |
| `app` | `apps/**` |
| `infra` | 빌드, CI, 도커 등 (scope 생략 가능) |

여러 도메인 동시 변경 시 scope 생략. 가능하면 커밋을 쪼개서 단일 scope로 유지.

### Subject 규칙

- 50자 이내
- 한글 권장 (팀 내부 소통이 한국어인 경우)
- 끝에 마침표 X
- 명령형/현재형 (`했음` X → `구현`, `추가` O)
- 첫 글자 소문자 (영문일 때)

### `refactor` vs `perf` 구분

| 변경 | 분류 |
|---|---|
| 코드 구조만 정리, 성능 변화 없음 | `refactor` |
| 측정 가능한 성능 개선이 목적 | `perf` |
| 캐시 추가, 인덱스 추가, N+1 해결 | `perf` |
| 알고리즘 복잡도 개선 | `perf` |
| 비동기·배치 처리로 전환 | `perf` |

### 예시

```
feat(live): LiveKit HLS Egress 시작 로직 구현 [SPR-123]

방송 시작 시 SFU 토큰 발급과 동시에 HLS Egress를 트리거.
시청자는 CDN을 통해 HLS URL로 즉시 접근 가능.

- LiveKitMediaManager.startHlsEgress 추가
- StartBroadcastService에서 도메인 상태 전이 후 발행
```

```
fix(order): 결제 콜백 중복 수신 시 멱등 처리 [SPR-145]
```

```
perf(live): 좋아요 카운트 200ms 집계 후 브로드캐스트 [SPR-210]

초당 수천 건의 개별 좋아요를 그대로 STOMP로 뿌리면
시청자 측 렌더링 부하가 큼. Redis INCR로 집계 후
스케줄러가 200ms 주기로 총 카운트만 송출.  

벤치마크: 1000 동시 시청자 기준 클라이언트 CPU 78% → 12%
```

```
refactor(live): out port를 application 레이어로 이동 [SPR-167]
```

```
chore: Spring Boot 4.0.1로 업그레이드 [SPR-180]
```

### Breaking Change

```
feat(order): 주문 생성 시 재고 선점 로직 분리 [SPR-200]

라이브 중 동시 주문 폭주 시 재고 음수 진입을 막기 위해
재고 선점을 별도 트랜잭션으로 분리.

BREAKING CHANGE: ProductRepository.decreaseStock 시그니처 변경
Refs: SPR-198, SPR-201
```

### Jira Smart Commits (선택)

| 명령어 | 동작 |
|---|---|
| `#comment <텍스트>` | Jira에 코멘트 추가 |
| `#time <시간>` | 작업 시간 기록 (예: `1h 30m`) |
| `#close` / `#resolve` / `#in-progress` | 상태 전이 |

```
fix(payment): null 체크 추가 [SPR-145] #comment 핫픽스 적용 #time 30m
```

---

## MR 컨벤션

### 제목

```
[JIRA-KEY] <type>(<scope>): <subject>
```

예시:

```
[SPR-123] feat(live): LiveKit HLS Egress 통합
```

### 템플릿

MR 템플릿은 `.gitlab/merge_request_templates/default.md`에 있으며, MR 생성 화면의 description 드롭다운에서 선택하면 자동 로드됩니다. (기본 브랜치 `dev`에 있어야 적용됨)

---

## 머지 전략

| 전략 | 용도 |
|---|---|
| **Squash and merge** | feature/fix 브랜치 → dev. 작은 커밋들을 1개로 압축 |
| **Merge commit** | dev → main (릴리즈). 머지 포인트가 명확하게 보임 |

GitLab MR 설정에서 "Squash commits when merging"을 활성화 권장.

---

## 한 페이지 요약

```
브랜치:  <type>/SPR-XXX
커밋:    <type>(<scope>): <한글 요약> [SPR-XXX]
MR:      [SPR-XXX] <type>(<scope>): <한글 요약>

type:    feat fix refactor perf test docs chore style ci
scope:   user customer seller product live order promotion notification
         common storage app infra

규칙:
  - 커밋·MR에 [SPR-XXX] 필수
  - main 직접 push 금지, MR만
  - MR은 Squash merge
  - subject 50자 이내, 마침표 X
  - perf는 측정 가능한 성능 개선에만 사용
```
