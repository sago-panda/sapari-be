package com.sapari.global.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 로그 기본값이 <b>기본값으로</b> 동작하는지 본다 — 없을 때 채우고, 있을 때 양보한다.
 *
 * <p>양보가 이 테스트의 핵심이다. 운영 설정은 추적되지 않는 {@code application*.yml} 이고, 커밋된
 * 기본값이 그것을 이기면 운영자가 고친 값이 조용히 무시된다. 같은 자리에 둘 수 있는
 * {@code application.properties} 를 쓰지 않은 이유가 정확히 그것이다(실측: properties 가 yml 을 이긴다).
 */
@DisplayName("StructuredLoggingDefaults")
class StructuredLoggingDefaultsTest {

    private final StructuredLoggingDefaults processor = new StructuredLoggingDefaults();

    /** 메인 클래스를 흉내 내기 위한 더미들 — 이름 유도만 쓴다. */
    static class StreamingAppApplication {
    }

    static class ApiAppApplication {
    }

    static class SomethingElse {
    }

    static class Application {
    }

    /**
     * 소스 클래스를 바꿔 넣고 환경을 돌린다.
     *
     * <p>{@code setMainApplicationClass} 를 쓰지 않는다 — 그 값은 스택트레이스 추론이라 Gradle 아래서
     * {@code GradleWorkerMain} 이 되고(실측), 그걸 테스트가 수동으로 덮으면 운영과 다른 경로를 재는 셈이다.
     */
    private StandardEnvironment process(Class<?>... sources) {
        StandardEnvironment environment = new StandardEnvironment();
        SpringApplication application = sources.length == 0
                ? new SpringApplication()
                : new SpringApplication(sources);
        processor.postProcessEnvironment(environment, application);
        return environment;
    }

    @Test
    @DisplayName("ECS 콘솔 포맷이 기본으로 켜진다 — MDC 가 그대로 JSON 필드가 되는 유일한 설정이다")
    void theConsoleFormatDefaultsToEcs() {
        assertThat(process(StreamingAppApplication.class)
                .getProperty("logging.structured.format.console")).isEqualTo("ecs");
    }

    @Test
    @DisplayName("스택트레이스에 상한이 걸린다 — 상한이 없으면 장애 중에 로그 비용이 폭증한다")
    void stackTracesAreBounded() {
        StandardEnvironment environment = process(StreamingAppApplication.class);

        assertThat(environment.getProperty("logging.structured.json.stacktrace.max-length", Integer.class))
                .isNotNull().isLessThanOrEqualTo(8192);
        assertThat(environment.getProperty("logging.structured.json.stacktrace.max-throwable-depth",
                Integer.class)).isNotNull().isPositive();
        assertThat(environment.getProperty("logging.structured.json.stacktrace.include-common-frames",
                Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("⭐ 잘릴 때 남는 쪽이 근본 원인이다 — 래퍼만 남은 스택은 아무 진단이 안 된다")
    void theRootCauseIsWrittenFirst() {
        assertThat(process(StreamingAppApplication.class)
                .getProperty("logging.structured.json.stacktrace.root")).isEqualTo("last");
    }

    @Test
    @DisplayName("⭐ 앱 이름이 소스 클래스에서 유도된다 — 다섯 앱 로그가 섞여도 service.name 으로 갈린다")
    void theServiceNameIsDerivedFromTheSourceClass() {
        assertThat(process(StreamingAppApplication.class).getProperty("spring.application.name"))
                .isEqualTo("streaming-app");
        assertThat(process(ApiAppApplication.class).getProperty("spring.application.name"))
                .isEqualTo("api-app");
    }

    @Test
    @DisplayName("Application 으로 끝나지 않으면 이름을 짓지 않는다 — 추측한 이름보다 빈 값이 낫다")
    void anUnexpectedSourceYieldsNoName() {
        assertThat(process(SomethingElse.class).getProperty("spring.application.name")).isNull();
        assertThat(process().getProperty("spring.application.name")).isNull();
    }

    @Test
    @DisplayName("⭐ 후보가 둘이면 짓지 않는다 — 틀린 이름은 틀렸다는 사실조차 안 보인다")
    void ambiguousSourcesYieldNoName() {
        assertThat(process(StreamingAppApplication.class, ApiAppApplication.class)
                .getProperty("spring.application.name")).isNull();
    }

    @Test
    @DisplayName("이름이 Application 뿐이면 빈 문자열을 짓지 않는다")
    void aBareApplicationClassYieldsNoName() {
        assertThat(process(Application.class).getProperty("spring.application.name")).isNull();
    }

    @Test
    @DisplayName("⭐ 운영 설정이 전부 이긴다 — 커밋된 기본값이 추적되지 않는 yml 을 덮어쓰면 안 된다")
    void operatorConfigurationWins() {
        // given: 운영자가 이미 정한 값들
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("operator", Map.of(
                "logging.structured.format.console", "logstash",
                "spring.application.name", "chat-gateway",
                "logging.structured.json.stacktrace.max-length", 99)));

        // when
        SpringApplication application = new SpringApplication(StreamingAppApplication.class);
        application.setMainApplicationClass(StreamingAppApplication.class);
        processor.postProcessEnvironment(environment, application);

        // then
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
        assertThat(environment.getProperty("spring.application.name")).isEqualTo("chat-gateway");
        assertThat(environment.getProperty("logging.structured.json.stacktrace.max-length")).isEqualTo("99");
    }

    @Test
    @DisplayName("기본값 소스에 이름이 붙는다 — 환경 덤프에서 \"이 값이 어디서 왔나\"를 읽을 수 있게")
    void theDefaultsCarryANamedSource() {
        assertThat(process(StreamingAppApplication.class).getPropertySources()
                .contains(StructuredLoggingDefaults.SOURCE_NAME)).isTrue();
    }
}
