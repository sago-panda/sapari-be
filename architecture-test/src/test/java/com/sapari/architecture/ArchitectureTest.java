package com.sapari.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
    private static boolean isCoreLayer(JavaClass clazz) {
        String[] parts = clazz.getPackageName().split("\\.");
        return parts.length >= 4 && CORE_LAYERS.contains(parts[3]);
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
}
