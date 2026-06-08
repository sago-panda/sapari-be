# 패키지 구조 가이드 — modules

> "각 패키지에 어떤 클래스가 들어가야 하는지"에 대한 문서입니다.
> 기준은 **레퍼런스 구현인 `live` 모듈**이며, 각 모듈마다 추가, 제외되는 디렉토리가 존재할 수 있습니다.
>
> - 모듈 간 의존 규칙(누가 누구를 의존하나) → 루트 `AGENTS.md` 내지 `README.md`에 작성되어 있습니다.
> - 모듈별 내부 패턴·의도적 예외 → 각 모듈 `AGENTS.md` (예: `modules/live/AGENTS.md`)에 작성되어 있고 작업 후 추가하셔야 후속 작업에 유용합니다.
> - 여기 규칙 대부분은 **ArchUnit(`architecture-test`)이 빌드에서 강제**하도록 설정했고 → 어기면 CI과정에서 실패하여 머지가 불가능합니다.

---

## 1. 큰 그림 — 도메인 모듈 = `X-api` + `X-core`

모든 도메인 모듈은 두 개의 하위 모듈로 나뉩니다.

| | 무엇 | 누가 의존하나 |
|---|---|---|
| **`X-api`** | 공개 **계약** — 입력(command) · 유스케이스(port) · 출력(view/result) | 컨트롤러(apps), **다른 도메인** |
| **`X-core`** | **구현** — 도메인 로직 · DB · 외부 연동 (비공개) | 자기 자신만 (런타임에 앱이 조립) |

**핵심 원칙: 남(컨트롤러·다른 도메인)은 `X-api`(메뉴판)만 본다. `X-core`(주방)는 절대 직접 접근하지 않습니다.**
→ ArchUnit 룰4·5가 이를 강제합니다.

---

## 2. live 모듈 전체 트리 (레퍼런스)

```
modules/live/
├─ live-api/   src/main/java/com/sapari/live/
│  ├─ command/   CreateLiveCommand, StartLiveCommand, EnterLiveCommand, EndLiveCommand, GetLiveCommand
│  ├─ port/      CreateLiveUseCase, StartLiveUseCase, EnterLiveUseCase, EndLiveUseCase, GetLiveUseCase
│  └─ view/      CreateLiveView, StartLiveResult, EnterLiveResult, GetLiveResult
│
└─ live-core/  src/main/java/com/sapari/live/
   ├─ application/
   │  ├─ service/   CreateLiveService, StartLiveService, EnterLiveService, EndLiveService, GetLiveService
   │  │             └ *UseCase 구현. @Transactional 은 이 계층에만.
   │  └─ port/      LiveMediaManager(아웃바운드 포트) + HlsEgressResult, SfuRoomResult(포트 결과 record)
   ├─ domain/
   │  ├─ model/       LiveRoom, LiveProduct, StreamInfo(VO), LiveStatus(sealed), LiveRoomCache
   │  ├─ repository/  LiveRoomRepository, LiveProductRepository, LiveRoomCacheRepository  ← 레포 포트(인터페이스)
   │  └─ exception/   LiveDomainException(base), LiveNotFoundException, InvalidLiveStateException,
   │                  LiveMediaException, LiveErrorCode(enum)
   └─ infrastructure/
      ├─ media/        LiveKitMediaManager          ← LiveMediaManager 포트 구현(외부 SDK)
      ├─ persistence/
      │  ├─ entity/      LiveRoomEntity, LiveProductEntity, LiveRoomStatus, VodStatus  ← @Entity 는 여기만
      │  ├─ mapper/      LiveRoomMapper             ← Entity ↔ domain record 변환
      │  └─ repository/  LiveRoomJpaRepository, LiveRoomRepositoryImpl, LiveProductJpaRepository, LiveProductRepositoryImpl
      ├─ redis/        LiveRoomCacheRedisRepository(캐시 포트 구현), LiveRedisKeys
      └─ config/       LiveKitConfig, LiveKitProperties
```

각 모듈별로 어떠한 내용들이 추가될 수 있고, 또는 빠질 수 있습니다. 이는 해당 파트를 담당하는 팀원이 개인으로 판단할 수 있고, 또는 팀과 협의 후 판단할 수 있습니다.
예를 들어 자기가 담당한 도메인에만 필요하다 판단했지만 다른 도메인에도 필요해서 common으로 빼는 것이 맞는 경우가 있을 수 있습니다. 신중히 판단해주시면 감사하겠습니다.

---

## 3. 패키지별 — 무엇을 넣나

### `X-api` (공개 계약 — 의존성 없는 순수 DTO/인터페이스)

| 패키지 | 넣는 것 | live 예시 | 규칙 |
|---|---|---|---|
| `command/` | 유스케이스 **입력** DTO (record) | `StartLiveCommand` | 요청 표현. 로직 없음 |
| `port/` | **인바운드 포트** = 유스케이스 인터페이스 | `StartLiveUseCase` | 컨트롤러가 이걸 주입 |
| `view/` *(또는 `result/`)* | 유스케이스 **출력** DTO (record) | `StartLiveResult` | 응답 표현. **엔티티 노출 금지** |
| `model/` | **여러 도메인이 공유하는** 값 타입 enum/VO | live엔 없음 (`user-api`엔 `UserRole` 등) | **공유될 때만** 둔다 |
| `event/` | 도메인 이벤트 | (있을 때) | cross-domain 협력 수단 |

> `X-api`는 `X-core`를 **절대 의존하지 않습니다**(룰4). 그래서 다른 도메인이 `X-api`만 가져가도 구현이 안 딸려옵니다.
> `view`는 Inbound Usecase의 응답 DTO, 즉 in-port인 use case가 컨트롤러에 돌려주는 것
> `result`는 Outbound 포트 반환 record 입니다. 
> 일부 구조에서 명칭의 혼선이 있었는데 이는 수정할 예정입니다. (26.06.04 기준)

### `X-core / application` (유스케이스 오케스트레이션)

| 패키지 | 넣는 것 | live 예시 | 규칙 |
|---|---|---|---|
| `application/service/` | `*Service implements *UseCase` | `StartLiveService` | **`@Transactional`은 여기에만.** SDK/JPA 직접 호출 금지 → 포트 경유 |
| `application/port/` | **아웃바운드 포트** + 그 결과 record | `LiveMediaManager`, `HlsEgressResult` | 외부 시스템(미디어 등) 추상화. 구현은 `infrastructure` |

### `X-core / domain` (가장 안쪽 — 순수)

| 패키지 | 넣는 것                             | live 예시 | 규칙 |
|---|----------------------------------|---|---|
| `domain/model/` | 도메인 record(엔티티) · sealed 상태 · VO | `LiveRoom`, `LiveStatus`(sealed), `StreamInfo` | **불변**(record). 상태전이는 새 인스턴스 반환. `@Entity`·가변필드 금지 |
| `domain/repository/` | 레포지토리 **포트**(인터페이스)              | `LiveRoomRepository` | 구현은 `infrastructure`. 도메인은 인터페이스만 안다 |
| `domain/exception/` | 도메인 예외 + 에러코드 enum               | `LiveNotFoundException`, `LiveErrorCode` | 예외는 `BusinessException` 상속(룰1). 서비스에서 raw `RuntimeException` 금지 |

> `domain/`은 `application`·`infrastructure`를 **의존하지 않습니다**(룰2 — 헥사고날: 도메인이 가장 안쪽).

### `X-core / infrastructure` (어댑터 — 바깥세상)

| 패키지 | 넣는 것 | live 예시 | 규칙 |
|---|---|---|---|
| `infrastructure/persistence/entity/` | **JPA `@Entity`** + 엔티티 enum | `LiveRoomEntity` | `@Entity`는 **오직 여기만**(룰3). 컨트롤러/서비스/도메인이 엔티티 직접 사용 금지(룰8) |
| `infrastructure/persistence/mapper/` | Entity ↔ domain record 변환 | `LiveRoomMapper` | 보통 static |
| `infrastructure/persistence/repository/` | Spring Data JPA repo + 레포 포트 구현 | `LiveRoomJpaRepository`, `LiveRoomRepositoryImpl` | `*RepositoryImpl`이 `domain/repository` 포트를 구현 |
| `infrastructure/<ext>/` | 외부 시스템 어댑터 (media·redis 등) | `LiveKitMediaManager`, `LiveRoomCacheRedisRepository` | `application`/`domain`의 포트를 구현 |
| `infrastructure/config/` | 모듈 Spring 설정·프로퍼티 | `LiveKitConfig`, `LiveKitProperties` | |

---

## 4. 의존 흐름 (한 요청이 흐르는 길)

```
[apps] Controller
   │  주입: X-api 의 *UseCase 포트  (command 입력)
   ▼
[X-api] *UseCase (인터페이스)                          ── view/result 로 응답
   │  런타임에 구현 주입
   ▼
[X-core] *Service  (@Transactional)
   ├─▶ domain/model            (불변 도메인 로직 · 상태전이)
   ├─▶ domain/repository(포트) ─▶ infrastructure/persistence  (JPA 어댑터)
   └─▶ application/port(포트)   ─▶ infrastructure/<ext>        (외부 SDK 어댑터)
```

- **안으로 갈수록 순수**(domain), **밖으로 갈수록 더러움**(infrastructure).
- 안쪽은 바깥쪽의 **인터페이스(포트)** 만 알고, 구현은 런타임에 주입된다(의존성 역전, DIP).

---

## 5. "이 클래스 어디 두지?" — 결정 가이드

```
요청 입력 DTO 인가?                    → X-api/command
응답 출력 DTO 인가?                    → X-api/view (또는 result)
유스케이스 진입 인터페이스인가?         → X-api/port            (예: XxxUseCase)
그 유스케이스 구현(@Transactional)?     → X-core/application/service
외부 시스템(미디어/결제…) 추상화?       → 포트:  X-core/application/port
                                         구현:  X-core/infrastructure/<ext>
핵심 비즈니스 규칙·상태를 가진 객체?     → X-core/domain/model          (record, 불변)
DB 조회/저장 인터페이스?               → 포트:  X-core/domain/repository
                                         구현:  X-core/infrastructure/persistence/repository
JPA @Entity 인가?                      → X-core/infrastructure/persistence/entity   (오직 여기)
Entity ↔ 도메인 변환?                  → X-core/infrastructure/persistence/mapper
도메인 규칙 위반 예외?                  → X-core/domain/exception       (BusinessException 상속)
여러 도메인이 함께 쓰는 값 타입?         → X-api/model (공유될 때만). 아니면 domain/model
```

---

## 6. `common/*` — 공유 토대(foundation)

`common/*`은 **도메인이 아닙니다.** 여러 모듈(모든 도메인·앱)이 함께 쓰는 횡단 관심사를 담는 패키지입니다.
그래서 `X-api`/`X-core`로 나누지 않고 **관심사별 모듈**로 쪼개져 있습니다.

| 모듈 | 패키지 | 무엇 | 예시 | 의존 |
|---|---|---|---|---|
| **`common/core`** | `com.sapari.common.core` | 순수 계약 — 웹·도메인 무관 | `BusinessException`, `ErrorCode`, `CommonErrorCode` | (없음 — 가장 안정) |
| **`common/global`** | `com.sapari.global` | 횡단 유틸 | `TimeProvider`, `UrlValidator` | (없음) |
| **`common/page`** | `com.sapari.common.page` | 페이지네이션 토대 (-api 반환타입 + 코덱) | `CursorPage`, `OffsetPage`, `PageSupport`, `CursorCodec`, `InvalidCursorException` | `core` |
| **`common/web`** | `com.sapari.common.web` | 웹·보안 공통 | `GlobalExceptionHandler`, `ErrorResponse`, JWT(`JwtTokenProvider`…), 보안 필터, `@CurrentUserId`, 토큰 **포트**(`RefreshTokenStore`·`AccessTokenBlacklist`), Swagger | `core`, `global` |
| **`common/auth`** | `com.sapari.common.auth` | 인증 인프라 **구현** | `RefreshTokenRedisRepository`, `AccessTokenBlacklistRedisRepository` (common/web 포트의 Redis 구현) | `common/web`, `storage:redis-core` |

> 주의: `common/global`만 패키지가 `com.sapari.global`(다른 셋은 `com.sapari.common.*`). 추후 수정할지는 의논 후 정하겠습니다.

**내부 레이어링** (안정 → 변동):
```
common/core  ·  common/global       (순수 토대, 의존 없음 — 모든 모듈이 깔고 쓰는 보편 리프)
        ▲
   common/page (→ core)             (페이지네이션 토대; -api 반환타입 + 코덱)
   common/web  (→ core, global)     (웹·보안 + 토큰 포트)
        ▲
   common/auth                       (Redis 구현)
```
- `common/*`은 **도메인 모듈을 의존하지 않습니다**(룰6). 내부에서는 `core`/`global` ← `page`/`web` ← `auth` 방향만.
- `core`·`global`은 **의존 0인 형제 리프**이며 서로 의존하지 않습니다(룰7 슬라이스 순환 방지). BusinessException 이 필요한 페이지네이션 코덱은 `global`(의존 0 불변)에 두지 않고 `core` 위의 `common/page`로 분리했습니다.
- **포트/구현 분리** 예: 토큰 **포트는 `common/web`**, **구현(Redis)은 `common/auth`** — `common/web`이 Redis에 매이지 않도록.

### 새 공통 요소가 생기면? — `common`에 무지성으로 절대 넣지 말 것

`common`은 **"지금까지의" 공통**만 담았습니다. 앞으로 새 횡단 관심사가 생기는데 **특정 도메인 것이 아니고
여러 모듈이 쓸 것**이라 판단되면 → 기존 모듈에 욱여넣지 말고 **응집된 새 모듈을 만드세요.**

**판단 기준 (셋 다 yes → 새 모듈):**
1. **둘 이상의 도메인/앱**이 쓰는가? (한 도메인만 쓰면 → 그 도메인 모듈로)
2. 특정 도메인에 **속하지 않는** 공통 관심사인가?
3. **단일 책임**으로 응집되는가?

**실제 사례 — `common/auth` 분리:** 토큰 저장소(Redis) 구현을 `common/web`에 넣을 수도 있었지만, 그러면
**모든 `common/web` 사용자가 Redis를 끌고 오게** 됩니다. 토큰 인프라는 별개 관심사라 `common/auth`로
분리해 `common/web`(포트)은 Redis-free로 유지했습니다.

> 즉 `common`은 **닫힌 목록이 아닙니다.** "여러 모듈 공통 + 도메인 무관 + 단일 책임"이면
> `common/<새이름>`(인프라성이면 `storage/<새이름>`)을 새로 만듭니다.

---

## 7. `storage/*` — 영속·캐시 기반(infra foundation)

`storage/*`도 도메인이 아닌 **인프라 토대**입니다. JPA·Redis를 쓰는 모든 모듈이 깔고 쓰는 공통 설정·베이스를 둡니다.

| 모듈 | 패키지 | 무엇 | 예시 |
|---|---|---|---|
| **`storage/db-core`** | `com.sapari.storage.db` | JPA 공통 — 엔티티 베이스·감사 | `BaseUuidEntity`·`BaseTimeEntity`(`@MappedSuperclass`), `JpaAuditConfig` |
| **`storage/redis-core`** | `com.sapari.storage.redis` | Redis 공통 — 템플릿·직렬화 설정 | `RedisConfig`(`StringRedisTemplate` 등) |

- 도메인 모듈의 `infrastructure`가 이걸 깔고 씁니다 — 예: `LiveRoomEntity extends BaseUuidEntity`, redis 레포가 `StringRedisTemplate` 주입.
- `common/*`과 마찬가지로 **도메인 모듈을 의존하지 않습니다**(룰6). 새 인프라 공통(예: S3·검색)이 여러 모듈에 필요하면 → `storage/<새이름>`을 새로 만듭니다(룰6의 "새 모듈" 규칙과 동일).

---

## 8. `apps/*` — 실행 가능한 조립체(executable)

각 app은 **하나의 부팅 가능한 Spring Boot 실행 단위**입니다. 비즈니스 로직을 만들지 않고, 필요한
`-core`·`common`·`storage`를 모아 **런타임에 조립**하며 HTTP/WS 진입점(컨트롤러)과 앱별 설정(보안·필터)을 둡니다.

| 앱 | 역할 |
|---|---|
| **`api-app`** | 구매자/판매자 REST API (member·seller·live 컨트롤러 + 보안 체인) |
| **`admin-app`** | 관리자 |
| **`streaming-app`** | 실시간 (WS 채팅 예정) |
| **`batch-app`** | 배치 |

**`api-app` 구성 예:**
```
apps/api-app/  src/main/java/com/sapari/apiapp/
├─ ApiAppApplication              ← main (컴포넌트 스캔 com.sapari)
├─ config/        ApiSecurityConfig(보안 체인), WebFilterConfig(MDC 필터),
│                 WebMvcConfig(@CurrentUserId 리졸버), SwaggerConfig
├─ controller/<도메인>/   LiveController, MemberAuthController, SellerAuthController
│                 └ dto/request·response/   HTTP 요청·응답 DTO
└─ controller/auth/   AuthCookieSupport, BearerTokenExtractor (인증 보조)
   resources/     application.yml, logback-spring.xml
```

| 무엇 | 어디 |
|---|---|
| HTTP/WS 진입점 (컨트롤러) | `controller/<도메인>/` — **`X-api`의 `*UseCase` 포트만 주입** |
| 컨트롤러 전용 요청·응답 DTO | `controller/.../dto/` — *HTTP 표현*. `X-api`의 command/view와 **다름**(컨트롤러에서 변환) |
| 앱 설정 (보안·필터·MVC·문서) | `config/` |
| 부팅·로깅·프로퍼티 | `*Application`, `resources/`(`application.yml`·`logback-spring.xml`) |

- **컨트롤러는 `X-core`를 *코드로* 직접 호출하지 않습니다** — `X-api`의 `*UseCase`만 (코드 컨벤션, sapari-reviewer가 검사).
- app이 `-core`를 *gradle 의존*으로 갖는 건 **런타임 빈 조립용**이라 허용됩니다(컨트롤러가 코드로 core를 부르는 것과 구분).
- **ArchUnit은 `apps`를 분석하지 않습니다**(앱은 조립 모듈). 컨트롤러 컨벤션은 리뷰어가 담당.
- 이후에 app모듈에도 필요한 내용이 추가될 수 있습니다. 이 부분은 개인 판단, 혹은 팀과 협의 후 진행하면 되겠습니다.

---

## 9. 빌드가 강제하는 것 (ArchUnit `architecture-test`)

어기면 **빌드 실패** — 리뷰 전에 ArchUnit이 먼저 잡습니다.

1. 도메인 예외는 `BusinessException` 상속 — `GlobalExceptionHandler`가 타입 하나로 일관 응답하기 위해.
2. `domain` ↛ `application`/`infrastructure` — 도메인을 외부 기술에 안 물든 가장 안쪽 순수 계층으로 유지.
3. `@Entity`는 `infrastructure.persistence.entity` 에만 — JPA가 도메인·응답으로 새는 것을 차단(도메인 순수성).
4. `X-api` ↛ `X-core` — 계약(api)이 구현(core)에 매이면 남이 api만 가져가도 구현이 딸려온다.
5. 도메인 `core` ↛ **다른 도메인** `core` — 남의 내부에 결합하면 그 도메인이 독립적으로 못 바뀐다(협력은 `X-api`로만).
6. 공유 토대(`common`/`global`/`storage`) ↛ 도메인 모듈 — 토대가 특정 도메인에 매이는 의존 역전을 막는다.
7. 도메인 슬라이스 간 **순환 의존** 금지 — 순환이 생기면 모듈을 독립적으로 이해·빌드·배포할 수 없다(ADP).
8. `@Entity`는 `infrastructure.persistence` 안에서만 참조 — 컨트롤러/서비스가 엔티티를 직접 다루면 mass-assignment(클라가 role·id 주입)·엔티티 노출 위험.

---

> 이 문서는 `modules` · `common` · `storage` · `apps` 배치를 다룹니다.
> 새 패키지·모듈이 생기면 여기에 반영해 주세요(특히 §6의 "새 모듈" 규칙).
