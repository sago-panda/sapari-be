# sapari

라이브 커머스 백엔드 — 모듈러 모놀리식 구조 (Spring Boot 4 / Java 21)

## 목차

- [패키지 구조](#패키지-구조)
- [레이어 역할](#레이어-역할)
- [모듈 의존 규칙](#모듈-의존-규칙)
- [브랜치 컨벤션](#브랜치-컨벤션)
- [커밋 컨벤션](#커밋-컨벤션)
- [MR 컨벤션](#mr-컨벤션)
- [머지 전략](#머지-전략)

---

## 패키지 구조

```
sapari-be/
├── settings.gradle.kts
├── build.gradle.kts
├── buildSrc/
│
├── apps/                          # 실행 가능한 애플리케이션 (entry points)
│   ├── api-app/                   # 사용자용 REST API
│   ├── admin-app/                 # 어드민/판매자
│   ├── streaming-app/             # 라이브 송출/시청 (WebSocket, SFU 연동)
│   └── batch-app/                 # 정산/통계 배치
│
├── commons/                       # 공용 기술 컴포넌트
│   ├── core/                      # 공용 예외, 이벤트 인프라, 분산락, 유틸
│   └── web/                       # 시큐리티, 응답 포맷, 웹 설정
│
├── storage/                       # 영속성 어댑터
│   ├── db-core/                   # JPA/QueryDSL/Flyway 공통
│   ├── redis-core/
│   ├── search-core/               # OpenSearch/ES
│   └── object-storage/            # S3
│
└── modules/                       # 도메인 모듈
    ├── user/                      # 회원 + 판매자
    │   ├── user-api/              # 외부 공개 계약 (다른 모듈이 의존)
    │   │   ├── command/           # 외부 입력 DTO
    │   │   ├── event/             # 도메인 이벤트
    │   │   ├── port/              # in port (Facade)
    │   │   └── view/              # 외부 응답 DTO
    │   └── user-core/             # 내부 구현 (패키지로 레이어 분리)
    │       ├── domain/model/
    │       ├── domain/repository/
    │       ├── domain/service/
    │       ├── application/service/
    │       ├── application/port/
    │       ├── infrastructure/persistence/
    │       └── infrastructure/config/
    │
    ├── product/                   # 상품 + 카탈로그 + 리뷰
    │   ├── product-api/
    │   └── product-core/
    │
    ├── live/                      # 라이브 방송 + 채팅
    │   ├── live-api/
    │   └── live-core/
    │       ├── domain/model/
    │       ├── domain/repository/
    │       ├── domain/service/
    │       ├── application/service/
    │       ├── application/port/
    │       ├── application/handler/
    │       ├── infrastructure/persistence/
    │       ├── infrastructure/media/      # LiveKit SFU 어댑터
    │       ├── infrastructure/messaging/  # Redis Pub/Sub, STOMP
    │       └── infrastructure/config/
    │
    ├── order/                     # 장바구니 + 주문 + 결제 + 배송
    │   ├── order-api/
    │   └── order-core/
    │       └── infrastructure/client/     # PG사, 배송 API
    │
    ├── promotion/                 # 쿠폰 + 포인트
    │   ├── promotion-api/
    │   └── promotion-core/
    │
    └── notification/              # 알림 (FCM, SMS)
        ├── notification-api/
        └── notification-core/
```

## 레이어 역할

| 레이어 | 역할 |
|---|---|
| `apps/*` | 실행 가능한 애플리케이션. Controller와 진입 설정만 포함 |
| `commons/*` | 도메인과 무관한 공용 기술 코드 |
| `storage/*` | DB/Redis/검색/스토리지 공통 설정 |
| `modules/*/​*-api` | 외부 모듈에 공개되는 계약 (DTO, 이벤트, Facade 인터페이스) |
| `modules/*/​*-core` | 도메인 + 애플리케이션 + 인프라 구현 (외부 비공개) |

## 모듈 의존 규칙

```
apps/*           → modules/*/​*-api,  modules/*/​*-core,  commons/*

modules/X/X-core → modules/X/X-api,  commons/*,  storage/*
                 → modules/Y/Y-api  (다른 도메인은 api만 의존)

modules/X/X-api  → (의존 없음, DTO/인터페이스만)
```

핵심 원칙:

- 도메인 간 직접 호출은 `*-api` (port 인터페이스) 통해서만
- 트랜잭션을 가로지르는 협력은 도메인 이벤트로 처리
- `*-core`는 외부 모듈이 절대 의존하지 않음 (Gradle `implementation`)

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
scope:   user product live order promotion notification
         common storage app infra

규칙:
  - 커밋·MR에 [SPR-XXX] 필수
  - main 직접 push 금지, MR만
  - MR은 Squash merge
  - subject 50자 이내, 마침표 X
  - perf는 측정 가능한 성능 개선에만 사용
```
