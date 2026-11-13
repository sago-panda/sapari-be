package com.sapari.architecture;

import java.lang.annotation.Annotation;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;

import com.sapari.chat.port.BlockingChat;
import com.sapari.chat.port.ReactiveChat;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 모듈 구조·헥사고날 규칙을 빌드에서 강제하는 아키텍처 테스트.
 * (CI의 test 태스크에 포함되어 위반 시 머지를 막는다)
 */
class ArchitectureTest {

    private static final JavaClasses SAPARI = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.sapari");

    // 1) 도메인 예외는 모두 BusinessException을 상속해야 한다 (GlobalExceptionHandler가 일관 처리 가능하도록)
    @Test
    void domain_exceptions_extend_BusinessException() {
        ArchRule rule = classes()
                .that().resideInAPackage("..domain.exception..")
                .and().areAssignableTo(Throwable.class)
                .should().beAssignableTo("com.sapari.common.core.exception.BusinessException");

        rule.check(SAPARI);
    }

    // 2) 도메인은 application/infrastructure를 의존하면 안 된다 (헥사고날: 도메인이 가장 안쪽)
    @Test
    void domain_must_not_depend_on_application_or_infrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..infrastructure..");

        rule.check(SAPARI);
    }

    // 3) JPA 엔티티(@Entity)는 infrastructure.persistence.entity 패키지에만 존재해야 한다 (도메인 순수성)
    @Test
    void jpa_entities_reside_in_persistence_entity_package() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().resideInAPackage("..infrastructure.persistence.entity..");

        rule.check(SAPARI);
    }

    // 4) *-api는 *-core(application/domain/infrastructure)를 의존하면 안 된다 (cross-domain은 api/이벤트만)
    @Test
    void api_must_not_depend_on_core() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.sapari.*.command..",
                        "com.sapari.*.port..",
                        "com.sapari.*.result..",
                        "com.sapari.*.view..",
                        "com.sapari.*.event..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..", "..domain..", "..infrastructure..");

        rule.check(SAPARI);
    }

    // 5) 한 도메인의 -core(application/domain/infrastructure)는 다른 도메인의 -core를 의존하면 안 된다.
    //    cross-domain 협력은 상대 도메인의 -api(port/command/result/view/model)로만 한다.
    //    (남의 육각형 내부 대신 published -api·common 토대에만 의존한다)
    @Test
    void core_must_not_depend_on_another_domains_core() {
        ArchRule rule = classes()
                .that().resideInAnyPackage(
                        "com.sapari.*.application..",
                        "com.sapari.*.domain..",
                        "com.sapari.*.infrastructure..")
                .should(dependOnAnotherDomainsCore());

        rule.check(SAPARI);
    }

    private static final Set<String> CORE_LAYERS = Set.of("application", "domain", "infrastructure");

    /** 대상 클래스가 "다른 도메인의 -core 레이어"이면 위반으로 기록하는 조건. */
    private static ArchCondition<JavaClass> dependOnAnotherDomainsCore() {
        return new ArchCondition<>("다른 도메인의 -core(application/domain/infrastructure)에 의존하지 않아야 한다") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                String originDomain = domainOf(origin);
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    if (isCoreLayer(target) && !domainOf(target).equals(originDomain)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    // com.sapari.<domain>.<layer>... 에서 <domain> 추출 (common/global 등 비도메인은 아래 isCoreLayer에서 걸러짐)
    private static String domainOf(JavaClass clazz) {
        String[] parts = clazz.getPackageName().split("\\.");
        return parts.length >= 3 ? parts[2] : "";
    }

    // <layer>가 application/domain/infrastructure 면 -core 내부로 본다 (common.core·common.web 등은 제외)
    // com.sapari 접두사를 반드시 확인한다 — 없으면 org.springframework.data.domain.* (Limit/Pageable/Sort)
    // 같은 서드파티가 parts[3]="domain"으로 걸려 "data 도메인의 core"라는 오탐이 난다.
    private static boolean isCoreLayer(JavaClass clazz) {
        String[] parts = clazz.getPackageName().split("\\.");
        return parts.length >= 4
                && "com".equals(parts[0]) && "sapari".equals(parts[1])
                && CORE_LAYERS.contains(parts[3]);
    }

    // 6) 공유 토대(common/global/storage)는 도메인 모듈(customer/seller/user/live...)을 의존하면 안 된다.
    //    (역전 방지: 공유 인증·유틸·스토리지가 특정 도메인에 매이면 안 됨)
    @Test
    void foundation_must_not_depend_on_domain_modules() {
        ArchRule rule = classes()
                .that().resideInAnyPackage("com.sapari.common..", "com.sapari.global..", "com.sapari.storage..")
                .should(dependOnDomainModule());

        rule.check(SAPARI);
    }

    // 7) 도메인 슬라이스 간 순환 의존 금지 (ADP: 비순환 의존 원칙).
    //    레이어(api/core) 무관하게 도메인끼리 양방향 의존(customer <-> user 등)을 잡는다.
    @Test
    void domain_slices_should_be_free_of_cycles() {
        SlicesRuleDefinition.slices()
                .matching("com.sapari.(*)..")
                .should().beFreeOfCycles()
                .check(SAPARI);
    }

    // 8) JPA 엔티티(@Entity)는 infrastructure.persistence 안에서만 참조되어야 한다 (보안: mass assignment 방어).
    //    컨트롤러/서비스/도메인이 엔티티를 직접 다루면 클라가 role·status·id 등을 주입(over-posting)하거나
    //    엔티티가 응답에 노출될 수 있다 → 요청 DTO·도메인 record로만 주고받는다.
    @Test
    void jpa_entities_are_only_used_within_persistence() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().onlyHaveDependentClassesThat().resideInAnyPackage("..infrastructure.persistence..");

        rule.check(SAPARI);
    }

    // 9) 공유 JWT 코덱 모듈(common/security-jwt)은 Servlet/웹MVC/스프링시큐리티에 의존하면 안 된다.
    //    (WebFlux 등 비-Servlet 소비자도 공유할 수 있도록 Servlet-free 유지 — JWT-A 추출 불변식)
    @Test
    void security_jwt_must_stay_servlet_free() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sapari.common.securityjwt..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..",
                        "org.springframework.web..",
                        "org.springframework.security..");

        rule.check(SAPARI);
    }

    // 표현 DTO 격리: 도메인 -core(application/domain/infrastructure)와 -api(command/port/result/view/event)는
    // 공통 응답 봉투(com.sapari.common.response: ResponseEnvelope/ErrorResponse)를 의존하면 안 된다.
    // 봉투는 컨트롤러(apps), 에러 봉투는 예외 핸들러(common/web)의 일이다 — use-case 포트가 봉투를 반환하지 않는다.
    @Test
    void domain_and_api_must_not_depend_on_response_types() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.sapari.*.application..",
                        "com.sapari.*.domain..",
                        "com.sapari.*.infrastructure..",
                        "com.sapari.*.command..",
                        "com.sapari.*.port..",
                        "com.sapari.*.result..",
                        "com.sapari.*.view..",
                        "com.sapari.*.event..")
                .should().dependOnClassesThat().resideInAPackage("com.sapari.common.response..");

        rule.check(SAPARI);
    }

    // *-api(command/port/result/view/event)는 com.sapari.global(TimeProvider 등 Spring 결합 토대)에 의존하면 안 된다.
    // use-case 반환 타입의 CursorPage/OffsetPage 는 별도 토대 모듈 com.sapari.common.page 로 분리했고, -api 는 그것만 의존한다.
    @Test
    void api_must_not_depend_on_spring_coupled_foundation() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.sapari.*.command..",
                        "com.sapari.*.port..",
                        "com.sapari.*.result..",
                        "com.sapari.*.view..",
                        "com.sapari.*.event..")
                .should().dependOnClassesThat().resideInAPackage("com.sapari.global..");

        rule.check(SAPARI);
    }

    // streaming-app(WebFlux)은 reactive 전용 — 블로킹 StringRedisTemplate/RedisTemplate을 주입·호출하면
    // Netty 이벤트루프가 블로킹된다. 블로킹 빈이 live-core 경유로 전이 존재하는 것은 허용하되,
    // streamingapp 패키지 코드가 그것을 '쓰는' 것만 금지한다.
    // (ReactiveStringRedisTemplate 등 reactive 타입은 같은 패키지라 패키지 단위가 아닌 타입명으로 차단)
    @Test
    void streaming_app_must_not_use_blocking_redis_template() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sapari.streamingapp..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.redis.core.StringRedisTemplate")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.data.redis.core.RedisTemplate")
                .as("streaming-app은 블로킹 Redis 템플릿 대신 ReactiveStringRedisTemplate만 사용해야 한다");

        rule.check(SAPARI);
    }

    // chat-core 한 모듈에 reactive 유스케이스(WS 전송)와 blocking 유스케이스(강퇴·이력 조회)가 함께 산다.
    // 두 앱이 chat-core를 의존하므로 양쪽 스택이 전이로 클래스패스에 올라오는데, 그 전이를 막으면 모듈 구조가
    // 깨지므로 허용하고 '호출'만 막는다 — 블로킹 Redis 금지와 같은 계열이다.
    //
    // 어느 쪽인지는 타입에 단 표시(@BlockingChat · @ReactiveChat)로 가른다. 전에는 단순 이름 목록이었는데,
    // 목록에 없는 이름은 아무것도 안 잡았다 — chat-api 포트 9개 중 3개만 들어 있었다(SPR-191 실측).
    // 표시는 구현체로 이어진다: 표시된 인터페이스를 구현한 클래스도 같은 쪽으로 본다.

    /** 그 표시가 붙었거나, 그 표시가 붙은 인터페이스를 구현한 타입. */
    private static DescribedPredicate<JavaClass> markedWith(Class<? extends Annotation> marker) {
        return DescribedPredicate.describe("@" + marker.getSimpleName() + " 이 붙었거나 그런 인터페이스를 구현한",
                javaClass -> javaClass.isAnnotatedWith(marker)
                        || javaClass.getAllRawInterfaces().stream().anyMatch(type -> type.isAnnotatedWith(marker)));
    }

    // WebFlux 앱이 blocking 유스케이스를 호출하면 Netty 이벤트루프가 그 시간만큼 멈춘다.
    @Test
    void streaming_app_must_not_call_blocking_chat_use_cases() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sapari.streamingapp..")
                .should().dependOnClassesThat(markedWith(BlockingChat.class))
                .orShould().dependOnClassesThat()
                        .haveFullyQualifiedName("org.springframework.data.mongodb.core.MongoTemplate")
                .as("streaming-app(WebFlux)은 블로킹 chat 유스케이스·블로킹 MongoTemplate을 호출하면 안 된다");

        rule.check(SAPARI);
    }

    // MVC 앱이 Mono/Flux를 반환하는 유스케이스를 호출하면 결국 .block()을 강요당한다.
    // 강퇴 REST가 얹히는 MVC 앱은 live-app이다. api-app은 chat 의존이 0건이라 대상에 넣지 않는다 —
    // 넣으면 그쪽 클래스가 항상 존재해서, live-app이 클래스패스에서 빠져도 빈 검사가 드러나지 않는다.
    @Test
    void live_app_must_not_call_reactive_chat_use_cases() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.sapari.liveapp..")
                .should().dependOnClassesThat(markedWith(ReactiveChat.class))
                .as("live-app(MVC)은 reactive chat 유스케이스를 호출하면 안 된다");

        rule.check(SAPARI);
    }

    // 위 두 규칙은 표시가 붙은 타입만 본다. 그래서 표시를 빠뜨리면 이름 목록과 같은 구멍이 다시 열린다.
    // chat-api 포트에 한해 그 구멍을 닫는다 — 포트는 둘 중 정확히 하나를 달아야 하고, 단 표시가
    // 시그니처와 맞아야 한다. chat-core 내부 타입(ChatKickRecorder 등)은 이 검사 밖이라 표시가 손에 달려 있다.
    @Test
    void every_chat_port_declares_whether_it_blocks() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.sapari.chat.port")
                .and().areInterfaces()
                .and(DescribedPredicate.describe("애너테이션 타입이 아닌", javaClass -> !javaClass.isAnnotation()))
                .should(new ArchCondition<JavaClass>("@BlockingChat 과 @ReactiveChat 중 정확히 하나를 달고, 그것이 반환형과 맞는다") {
                    @Override
                    public void check(JavaClass port, ConditionEvents events) {
                        boolean blocking = port.isAnnotatedWith(BlockingChat.class);
                        boolean reactive = port.isAnnotatedWith(ReactiveChat.class);
                        if (blocking == reactive) {
                            events.add(SimpleConditionEvent.violated(port,
                                    port.getName() + " 은 @BlockingChat 과 @ReactiveChat 중 정확히 하나를 달아야 한다"));
                            return;
                        }
                        for (var method : port.getMethods()) {
                            boolean returnsPublisher = method.getRawReturnType().isAssignableTo(Publisher.class);
                            if (returnsPublisher != reactive) {
                                events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                        + (reactive ? " 이 Publisher 를 반환하지 않는데 포트가 @ReactiveChat 이다"
                                                    : " 이 Publisher 를 반환하는데 포트가 @BlockingChat 이다")));
                            }
                        }
                    }
                });

        rule.check(SAPARI);
    }

    // OutboundMessage는 여덟 종류를 15개 nullable 필드 하나로 나르는 합집합이다. 위치 인자로 지으면
    // 한 칸 밀린 실수가 컴파일도 타입 검사도 통과한다 — 하필 senderEmail 바로 뒤가 displayMessage라,
    // 그 실수는 발신자 이메일을 본문 자리에 넣어 방 전원에게 뿌린다.
    //
    // 밖에서는 타입별 정적 팩토리로만 짓는다. 팩토리 안에서 위치 인자를 안 쓰게 하는 것은 빌더가
    // 맡고, 그 빌더는 package 접근이라 컴파일러가 이미 막는다 — 그래서 이 규칙은 생성자만 본다.
    //
    // 대상에서 빼는 것은 OutboundMessage와 그 중첩 클래스뿐이다. 빌더는 Lombok이 만든 중첩 클래스라
    // FQN이 다르고(OutboundMessage$OutboundMessageBuilder), 이름 하나만 비교하면 자기 빌더를 자기
    // 규칙이 잡는다(실제로 그랬다). 그렇다고 접두로 비교하면 같은 패키지의 OutboundMessageFactory 같은
    // 이름까지 조용히 면제된다 — 중첩 구분자 '$'까지 붙여 정확히 중첩만 뺀다.
    @Test
    void outbound_message_must_be_built_through_factories() {
        String outbound = "com.sapari.chat.application.protocol.OutboundMessage";

        ArchRule rule = noClasses()
                .that(DescribedPredicate.describe("OutboundMessage와 그 중첩 클래스가 아닌",
                        javaClass -> !javaClass.getName().equals(outbound)
                                && !javaClass.getName().startsWith(outbound + "$")))
                .should().callConstructorWhere(DescribedPredicate.describe(
                        "OutboundMessage 생성자",
                        target -> target.getTargetOwner().getName().equals(outbound)))
                .as("OutboundMessage는 타입별 정적 팩토리로만 생성해야 한다(위치 인자 밀림 = PII 유출)");

        rule.check(SAPARI);
    }

    // 10) @Transactional은 application 레이어에만 둔다.
    //     트랜잭션 경계는 유스케이스 오케스트레이션(application/service)의 책임 — 인프라/도메인/표현 계층에
    //     트랜잭션이 새면 경계가 흐려진다. (Spring Data 레포의 자체 트랜잭션은 여기 대상 아님)
    @Test
    void transactional_only_in_application_layer() {
        ArchRule rule = methods()
                .that().areAnnotatedWith("org.springframework.transaction.annotation.Transactional")
                .should().beDeclaredInClassesThat().resideInAPackage("..application..")
                .as("@Transactional은 application 레이어 메서드에만 선언되어야 한다");

        rule.check(SAPARI);
    }

    // 11) 현재 시각은 TimeProvider로만 얻는다 — application/domain에서 java.time *.now() 나
    //     System.currentTimeMillis()/nanoTime() 직접 호출 금지. (고정 시계 주입으로 테스트 가능성 확보)
    @Test
    void time_must_come_from_time_provider() {
        ArchRule rule = classes()
                .that().resideInAnyPackage("com.sapari..application..", "com.sapari..domain..")
                .should(notReadCurrentTimeDirectly());

        rule.check(SAPARI);
    }

    /** application/domain 클래스가 현재 시각을 직접 읽으면(= java.time *.now() / System 시간) 위반으로 기록. */
    private static ArchCondition<JavaClass> notReadCurrentTimeDirectly() {
        return new ArchCondition<>("현재 시각을 직접 읽지 않아야 한다 (TimeProvider 경유)") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (JavaMethodCall call : origin.getMethodCallsFromSelf()) {
                    String owner = call.getTarget().getOwner().getName();
                    String name = call.getTarget().getName();
                    boolean javaTimeNow = owner.startsWith("java.time.") && name.equals("now");
                    boolean systemTime = owner.equals("java.lang.System")
                            && (name.equals("currentTimeMillis") || name.equals("nanoTime"));
                    if (javaTimeNow || systemTime) {
                        events.add(SimpleConditionEvent.violated(call, call.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * 한 도메인의 -core(application)은 infrastructure를 직접 의존하면 안된다 (포트 경유)
     */
    @Test
    void application_must_not_depend_on_infrastructure(){
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.sapari.*.application.."
                ).should().dependOnClassesThat().resideInAPackage("..infrastructure..");

        rule.check(SAPARI);
    }

    /**
     * 도메인 계층(domain / application)은 관측 라이브러리를 몰라야 한다.
     *
     * <p>관측은 도구가 바뀌는 관심사다(micrometer → 다른 무엇). 서비스가 {@code MeterRegistry} 를 직접
     * 주입받기 시작하면 그 교체가 도메인 문장을 흔들고, 계측이 도메인 로직 사이에 섞여 "무슨 일이
     * 일어나는가" 를 읽기 어렵게 만든다. {@code LiveMetrics} 같은 포트를 두고 구현만 infrastructure 에
     * 두라는 규칙이며, 사람이 지키는 대신 빌드가 지킨다.
     */
    @Test
    void domain_and_application_must_not_depend_on_metrics_library(){
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.sapari.*.domain..",
                        "com.sapari.*.application.."
                ).should().dependOnClassesThat().resideInAPackage("io.micrometer..");

        rule.check(SAPARI);
    }

    private static final Set<String> NON_DOMAIN_ROOTS = Set.of("common", "global", "storage", "architecture");

    /** 대상이 도메인 모듈(com.sapari.<도메인>) 클래스이면 위반으로 기록하는 조건. */
    private static ArchCondition<JavaClass> dependOnDomainModule() {
        return new ArchCondition<>("도메인 모듈(customer/seller/user/live...)에 의존하지 않아야 한다") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    if (isDomainModuleClass(dependency.getTargetClass())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    // com.sapari.<X> 에서 X가 공유 토대(common/global/storage/architecture)가 아니면 도메인 모듈로 본다
    private static boolean isDomainModuleClass(JavaClass clazz) {
        String packageName = clazz.getPackageName();
        if (!packageName.startsWith("com.sapari.")) {
            return false;
        }
        String[] parts = packageName.split("\\.");
        return parts.length >= 3 && !NON_DOMAIN_ROOTS.contains(parts[2]);
    }

    /**
     * 20) MapStruct 매퍼는 미매핑 타깃에서 빌드를 깨야 한다.
     *
     * <p>기본 정책({@code WARN})이면 도메인 record 에 필드를 더하고 매퍼를 안 고쳤을 때 MapStruct 가
     * 그 타깃을 비워 둔 채 경고 한 줄만 내고 통과한다. 그러면 영속된 메시지를 읽을 때마다 그 필드가
     * 조용히 비어 있다.
     *
     * <p>매퍼 자신의 {@code unmappedTargetPolicy = ERROR} 가 그걸 막지만, <b>그 속성이 지워지는 것은
     * 아무것도 막지 않는다</b> — 지우는 시점에 미매핑 필드가 없으면 빌드도 테스트도 멀쩡하다. 그 구간을
     * 닫는 것이 이 규칙이다.
     *
     * <p>{@code @Mapper} 는 {@code RetentionPolicy.CLASS} 라 런타임 리플렉션으로는 못 읽지만 클래스
     * 파일에는 남고, ArchUnit 은 클래스 파일을 직접 읽는다. 속성을 아예 지운 경우에는 ArchUnit 이
     * <b>애너테이션 기본값을 채워</b> {@code WARN} 을 돌려주므로, 이 한 줄이 {@code IGNORE} 로의 명시적
     * 격하와 속성 삭제를 동시에 잡는다 — "속성이 있는가" 를 따로 볼 필요가 없다.
     *
     * <p>{@code live} 하나만 예외다 — 엔티티에 도메인 대응이 없는 영속 전용 필드(VOD·시청자 집계 등)가
     * 많아 비는 타깃이 정상이고, 그 사유가 {@code LiveRoomMapper} javadoc 에 적혀 있다. 나머지는
     * 이미 전부 {@code ERROR} 였으므로 이 규칙이 새로 금지하는 것은 없고, <b>그 상태가 유지되게</b> 한다.
     *
     * <p>한때 범위를 chat 으로만 잡았는데 그건 chat 작업 중이어서였고 근거가 아니었다. 결과가 가장
     * 무거운 자리는 다른 모듈이다 — {@code LocalCredentialMapper.toDomain} 이
     * {@code passwordHash}·{@code failedLoginCount}·{@code lockedAt} 를 담은 도메인 record 를 만든다.
     * 그쪽 정책이 지워진 뒤 필드가 늘면 <b>락아웃 상태가 조용히 빈다.</b>
     */
    @Test
    void mapstruct_mappers_must_fail_on_unmapped_targets() {
        ArchRule rule = classes()
                .that().resideInAPackage("com.sapari..")
                .and().resideOutsideOfPackage("com.sapari.live..")
                .and().areAnnotatedWith("org.mapstruct.Mapper")
                .should(declareUnmappedTargetPolicyError());

        rule.check(SAPARI);
    }

    /** {@code @Mapper} 의 {@code unmappedTargetPolicy} 를 클래스 파일에서 읽어 ERROR 인지 본다. */
    private static ArchCondition<JavaClass> declareUnmappedTargetPolicyError() {
        return new ArchCondition<>("unmappedTargetPolicy = ERROR 로 선언되어야 한다") {
            @Override
            public void check(JavaClass mapper, ConditionEvents events) {
                com.tngtech.archunit.core.domain.JavaAnnotation<?> annotation =
                        mapper.getAnnotationOfType("org.mapstruct.Mapper");
                String policy = annotation.get("unmappedTargetPolicy")
                        .map(value -> value instanceof com.tngtech.archunit.core.domain.JavaEnumConstant constant
                                ? constant.name()
                                : String.valueOf(value))
                        .orElse("(속성 없음 — ArchUnit 이 기본값도 못 채웠다)");
                events.add(new SimpleConditionEvent(mapper, "ERROR".equals(policy),
                        mapper.getName() + " 의 unmappedTargetPolicy = " + policy));
            }
        };
    }
    /**
     * 욕설 필터를 갈아 끼우는 포트를 <b>한 곳만</b> 쓴다.
     *
     * <p>이 규칙이 떠받치는 것은 다른 파일의 <b>삭제된 방어</b>다.
     * {@code ProfanityDictionaryRefreshHandler} 는 트리거 셋을 {@code concatMap} 으로 직렬화해
     * 적용자가 하나이고, 그 사실 위에서 단조 버전 비교({@code snapshot.version() <= local} 이면
     * 버림)를 <b>걷어냈다</b> — 그 비교가 "버전이 뒤로 간" 스냅샷을 조용히 버려서 Redis 가 상태를
     * 잃은 Pod 이 함대와 영구히 갈리는 무음 분기를 만들었기 때문이다.
     *
     * <p>그래서 "적용자가 하나" 가 주석이 아니라 <b>구조</b>여야 한다. 두 번째 구독이나 두 번째
     * 주입처가 생기면 두 적용이 겹칠 수 있고, 그때 더 낡은 사전이 나중에 깔려도 막는 것이 없다 —
     * 그 방어를 이미 지웠으니까. 이 규칙이 그 전제를 깨는 변경을 빌드에서 세운다.
     *
     * <p><b>타입이 아니라 메서드를 건다.</b> 처음에 포트 <i>타입</i>의 의존자를 셌는데 구멍이
     * 있었다 — 빈 선언 타입이 <b>구체 클래스</b>({@code ChatBeansConfig} 가
     * {@code SwappableProfanityFilter} 를 돌려준다)이고 {@code install} 이 공개라, 포트를 안
     * 거치고 구체 클래스를 주입받아 부르는 코드는 그 규칙을 <b>비껴간다.</b> 실측으로 확인했다
     * (2026-11-05): 앱 패키지에 그런 적용자를 심으면 21건 전부 초록이었다. 응용 계층에 심으면
     * {@code application_must_not_depend_on_infrastructure} 가 잡지만, 빈이 주입되는 자리는
     * 앱 패키지라 그 경로가 현실적인 쪽이다.
     *
     * <p>메서드를 걸면 두 경로가 한 규칙으로 닫히고, <b>셀을 만드는 것</b>(호스트 앱의
     * {@code @Bean})은 그대로 허용된다 — 막아야 하는 것은 생성이 아니라 <i>호출</i>이다.
     * 그래서 허용 목록이 핸들러 하나다: 셀은 {@code install} 을 <b>선언</b>할 뿐 부르지 않고,
     * 설정은 생성자만 부른다.
     *
     * <p><b>이 규칙은 조용해질 수 없다</b> — 이름을 문자열로 박았으니 클래스나 메서드가 옮겨지면
     * 대상이 0건이 되어 "호출자 없음" 으로 초록이 될까 물을 자리다. 실측(2026-11-05), 두 축 모두
     * <b>실패</b>한다: 메서드명을 없는 이름으로 바꾸면 이 규칙이 깨지고(ArchUnit 이 빈 집합을
     * 거부한다 — {@code architecture-test/build.gradle} 이 {@code failOnEmptyShould} 기본값에
     * 이미 하중을 걸고 있다), 선언 클래스 FQN 을 바꿔도 같다. 허용 목록 쪽도 실패 방향이다 —
     * 핸들러가 옮겨지면 그 호출이 위반으로 잡힌다. 조용한 통과가 되는 축이 없다.
     *
     * <p>세 경로를 되돌림으로 확인했다(2026-11-05) — 포트 경유 · 앱 패키지에서 구체 클래스 경유 ·
     * infrastructure 에서 구체 클래스 경유. 셋 다 이 규칙 이름으로 빨개진다. 가운데가 타입을
     * 걸었을 때 <b>통과하던</b> 경로다.
     */
    @Test
    void only_the_refresh_handler_may_install_a_profanity_filter() {
        ArchRule rule = methods()
                .that().haveName("install")
                .and().areDeclaredInClassesThat().haveNameMatching(
                        "com\\.sapari\\.chat\\.application\\.port\\.ProfanityFilterInstaller"
                                + "|com\\.sapari\\.chat\\.infrastructure\\.config\\.SwappableProfanityFilter")
                .should().onlyBeCalled().byClassesThat()
                // haveNameMatching 은 FQN 에 맞는다. 패키지로 거는 쪽(resideInAnyPackage)이 이
                // 저장소의 선례지만 여기서는 쓰지 않는다 — 막아야 하는 것이 "그 패키지 밖에서
                // 쓰는 것" 이 아니라 <적용자가 둘이 되는 것>이라, 같은 패키지에 두 번째가
                // 생기는 경우를 통과시키면 규칙이 자기 목적을 비껴간다.
                .haveFullyQualifiedName(
                        "com.sapari.chat.application.handler.ProfanityDictionaryRefreshHandler");

        rule.check(SAPARI);
    }

}
