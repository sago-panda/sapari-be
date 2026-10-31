package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 관측 노출 가드.
 *
 * <p>이 앱에는 Spring Security 가 없어서 노출 목록이 곧 무인증 공개다. 그래서 두 가지를 본다 —
 * 관리 포트가 사용자 포트와 다른지, 그리고 목록에 허용 밖 값이 없는지.
 *
 * <p><b>리스트 모양을 반드시 함께 본다.</b> 같은 설정을 {@code include: health,prometheus} 로도
 * YAML 리스트로도 쓸 수 있고, 후자는 프로퍼티 소스에 {@code include[0]} 으로 들어간다. 기본 키만
 * 읽는 구현이면 <b>리스트로 쓴 "*" 를 못 보고 통과시킨다</b> — 가장 위험한 입력이 가장 흔한 표기로
 * 통과하는 셈이다.
 */
@DisplayName("ObservabilityExposureConfig — 노출은 코드가 잠근다")
class ObservabilityExposureConfigTest {

    private final ObservabilityExposureConfig config = new ObservabilityExposureConfig();

    private Environment withExposure(String... includes) {
        StandardEnvironment environment = new StandardEnvironment();
        Map<String, Object> source = new java.util.HashMap<>();
        for (int i = 0; i < includes.length; i++) {
            source.put("management.endpoints.web.exposure.include[" + i + "]", includes[i]);
        }
        environment.getPropertySources().addFirst(new MapPropertySource("test", source));
        return environment;
    }

    private Environment withScalarExposure(String value) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoints.web.exposure.include", value);
        return environment;
    }

    @Test
    @DisplayName("관리 포트가 비어 있으면 부팅을 막는다 — 비면 액추에이터가 사용자 포트에 열린다")
    void anEmptyManagementPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "", withScalarExposure("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port 가 없다");
    }

    @Test
    @DisplayName("관리 포트가 사용자 포트와 같으면 부팅을 막는다")
    void anIdenticalPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8080", withScalarExposure("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.port 와 같다");
    }

    @Test
    @DisplayName("음수 포트는 부팅을 막는다 — -1 은 액추에이터를 끄는 값이라 지표가 조용히 사라진다")
    void aDisabledManagementPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "-1", withScalarExposure("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("숫자가 아니면 친절하게 막는다 — 스택트레이스만 남으면 원인이 오타라는 게 안 읽힌다")
    void aNonNumericPortIsRejectedWithItsValue() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "팔공구이", withScalarExposure("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("숫자가 아니다")
                .hasMessageContaining("팔공구이");
    }

    @Test
    @DisplayName("둘 다 0 이면 통과한다 — 0 은 포트가 아니라 \"빈 포트를 골라라\" 이고 통합 테스트가 그걸 쓴다")
    void bothRandomPortsAreAllowed() {
        assertThat(config.managementPortMustDiffer(0, "0", withScalarExposure("health"))).isNotNull();
    }

    @Test
    @DisplayName("health·prometheus 는 통과한다 — 좁힌 값이 아니라 기본값에 prometheus 를 더한 값이다")
    void theAllowedPairPasses() {
        assertThatCode(() -> config.managementPortMustDiffer(8080, "8092",
                withScalarExposure("health,prometheus"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ \"*\" 는 부팅을 막는다 — /actuator/env 가 무인증으로 Mongo 자격증명을 내보낸다")
    void aWildcardExposureIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8092", withScalarExposure("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("허용되지 않은 값");
    }

    @Test
    @DisplayName("⭐ env 를 하나 더해도 막는다 — 허용 집합 밖은 전부 걸린다")
    void anExtraEndpointIsRejectedWithItsName() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8092",
                withScalarExposure("health,prometheus,env")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("env");
    }

    @Test
    @DisplayName("⭐ YAML 리스트로 쓴 \"*\" 도 막는다 — 기본 키만 읽으면 이 모양이 통과한다")
    void aWildcardWrittenAsAYamlListIsAlsoRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8092", withExposure("*")))
                .as("리스트 표기를 못 보면 가장 위험한 입력이 가장 흔한 표기로 통과한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("허용되지 않은 값");
    }

    @Test
    @DisplayName("YAML 리스트로 쓴 허용 쌍은 통과한다")
    void theAllowedPairAsAYamlListPasses() {
        assertThatCode(() -> config.managementPortMustDiffer(8080, "8092",
                withExposure("health", "prometheus"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("대소문자·공백은 가드를 우회하지 못한다")
    void caseAndWhitespaceDoNotBypassTheGuard() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8092",
                withExposure(" ENV ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("env");
    }
}
