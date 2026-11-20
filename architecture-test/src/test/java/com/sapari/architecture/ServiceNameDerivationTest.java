package com.sapari.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.StandardEnvironment;

import com.sapari.global.logging.StructuredLoggingDefaults;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 다섯 앱의 ECS {@code service.name} 을 한 자리에 못박는다.
 *
 * <p><b>왜 이 모듈인가.</b> 그 값은 {@code StructuredLoggingDefaults} 가 <b>앱 클래스 이름에서</b>
 * 유도한다({@code StreamingAppApplication} → {@code streaming-app}). 유도의 입력이 앱에 있고 규칙은
 * {@code common/global} 에 있어서, 둘을 한꺼번에 볼 수 있는 모듈이 여기뿐이다 —
 * {@code common/global} 은 앱을 의존할 수 없고(방향이 반대다), 앱마다 같은 테스트를 다섯 번 쓰면
 * 새 앱이 생길 때 여섯 번째를 빼먹는다. 이 모듈은 다섯 앱을 전부 test 의존으로 끌어오므로
 * <b>발견</b>으로 검사할 수 있다.
 *
 * <p><b>무엇이 비어 있었나.</b> {@code common/global} 의 유도 테스트는 <b>같은 이름의 중첩 대역</b>
 * ({@code static class ApiAppApplication {}})을 쓴다. 그래서 실제 앱 클래스를 개명하면 유도값이
 * 조용히 바뀌고 저장소의 어떤 테스트도 빨개지지 않았다 — 실제 앱 클래스 이름을 참조하는 자리는
 * 컴포넌트스캔·자동설정 제외 테스트뿐이고 그 둘은 이름 유도를 보지 않는다.
 *
 * <p>api-app 에서 특히 비어 있었다. 이 브랜치가 그 앱의 {@code logback-spring.xml} 을 지우고
 * 이 EPP 로 대체했는데, api-app 의 테스트에는 실제 앱을 띄우는 것이 하나도 없고 중첩
 * {@code TestApplication} 만 있어서 그 앱의 유도값이 {@code test} 였다.
 *
 * <p><b>발견으로 검사하는 이유</b>: 클래스를 직접 적으면 개명 시 이 파일이 컴파일에서 깨져 손이
 * 가지만, 새 앱이 규약을 어겨도 아무 일이 없다. 기대값을 <b>집합 전체</b>로 못박으면 개명·추가·
 * 삭제가 모두 걸린다.
 */
@DisplayName("service.name 유도 — 다섯 앱의 ECS 서비스 이름을 집합으로 못박는다")
class ServiceNameDerivationTest {

    /**
     * 기대 집합. 왼쪽이 앱 클래스 단순명, 오른쪽이 ECS {@code service.name} 이다.
     *
     * <p>대시보드·알림 쿼리가 오른쪽 값을 키로 쓴다. 왼쪽을 개명하면 오른쪽이 따라 바뀌는데
     * 그건 로그 쪽에서 <b>빈 패널</b>로만 보인다 — 그래서 이 표가 계약이다.
     */
    private static final Map<String, String> EXPECTED = Map.of(
            "AdminAppApplication", "admin-app",
            "ApiAppApplication", "api-app",
            "BatchAppApplication", "batch-app",
            "LiveAppApplication", "live-app",
            "StreamingAppApplication", "streaming-app");

    private static final JavaClasses SAPARI = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.sapari");

    @Test
    @DisplayName("⭐ @SpringBootApplication 클래스마다 유도되는 이름이 기대 집합과 정확히 같다")
    void everyAppDerivesItsServiceName() {
        // when: 발견한 앱 클래스마다 EPP 를 실제로 돌려 유도값을 받는다.
        // 컨텍스트를 띄우지 않는다 — new SpringApplication(..) 은 소스만 등록하고 run() 을 하지 않는다.
        Map<String, String> derived = new TreeMap<>();
        for (JavaClass candidate : SAPARI) {
            if (!candidate.isAnnotatedWith(SpringBootApplication.class)) {
                continue;
            }
            Class<?> appClass = candidate.reflect();
            derived.put(appClass.getSimpleName(), deriveServiceName(appClass));
        }

        // then: 포함이 아니라 동등이다 — 새 앱이 규약을 어기면 이쪽에만 나타나므로 걸린다.
        assertThat(derived)
                .as("앱 클래스 이름에서 유도되는 service.name 이 바뀌었다 — 로그 쪽에서는 빈 패널로만 보인다")
                .containsExactlyInAnyOrderEntriesOf(EXPECTED);
    }

    /** 그 앱 클래스 하나를 소스로 준 EPP 가 심는 {@code spring.application.name}. 없으면 빈 문자열. */
    private static String deriveServiceName(Class<?> appClass) {
        StandardEnvironment environment = new StandardEnvironment();
        new StructuredLoggingDefaults()
                .postProcessEnvironment(environment, new SpringApplication(appClass));
        String name = environment.getProperty("spring.application.name");
        return name == null ? "" : name;
    }
}
