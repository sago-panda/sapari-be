package com.sapari.liveapp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리 포트 분리가 <b>실제로 지켜지는지</b>를 부팅 시점에 검증하는 가드의 테스트.
 *
 * <p>이 가드가 필요한 이유는 {@code actuatorFilterChain} 이 액추에이터를 {@code permitAll} 하기
 * 때문이다(스크레이퍼는 로그인할 수 없다). 그 개방은 "관리 포트가 사용자 포트와 다르다" 는 전제
 * 위에서만 안전한데, 전제가 설정 한 줄로 깨질 수 있고 깨져도 앱은 멀쩡히 뜬다 — 영업 지표가
 * 사용자 포트에 열린 채로. 그래서 주석이 아니라 부팅 실패로 막는다.
 */
class ManagementPortGuardTest {

    private final LiveSecurityConfig config = new LiveSecurityConfig();

    /**
     * 노출 목록이 갖춰진 환경 — 포트 검사만 보려는 테스트가 노출 가드에 걸리지 않게 한다.
     *
     * <p>두 검사를 한 빈에 둔 이유는 둘이 <b>같은 전제</b>를 지키기 때문이다: "지표는 관리 포트에만
     * 있고, 거기서 실제로 읽힌다". 포트만 나뉘고 엔드포인트가 없으면 스크레이퍼가 401 을 받아
     * 지표가 조용히 빈다 — 포트가 합쳐진 것과 방향만 반대인 같은 실패다.
     */
    private static Environment exposingPrometheus() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("management.endpoints.web.exposure.include", "health,prometheus");
        return environment;
    }

    private static Environment withExposure(String include) {
        MockEnvironment environment = new MockEnvironment();
        if (include != null) {
            environment.setProperty("management.endpoints.web.exposure.include", include);
        }
        return environment;
    }

    @Test
    @DisplayName("포트가 다르면 통과한다")
    void differentPorts_pass() {
        assertThat(config.managementPortMustDiffer(8080, "8081", exposingPrometheus())).isNotNull();
    }

    @Test
    @DisplayName("포트가 같으면 부팅을 실패시킨다 — 지표가 사용자 트래픽 포트에 노출된다")
    void samePort_failsStartup() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8080", exposingPrometheus()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 트래픽 포트");
    }

    @Test
    @DisplayName("둘 다 0(랜덤 포트)이면 통과한다 — 실행 시점에 서로 다른 포트가 잡히므로 같은 포트가 아니다")
    void bothRandomPorts_pass() {
        assertThat(config.managementPortMustDiffer(0, "0", exposingPrometheus())).isNotNull();
    }

    @Test
    @DisplayName("관리 포트 설정 자체가 없으면 실패시킨다 — 기본값은 사용자 포트와 같다")
    void missingManagementPort_failsStartup() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "", exposingPrometheus()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("⭐ 노출 목록에 prometheus 가 없으면 부팅을 실패시킨다 — 스크레이퍼가 401 을 받고 지표가 조용히 빈다")
    void missingPrometheusExposure_failsStartup() {
        // given: 액추에이터 기본 노출(health 하나)만 있는 상태
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8091", withExposure("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prometheus 가 없다");
    }

    @Test
    @DisplayName("⭐ 노출 설정 자체가 없으면 실패시킨다 — 기본값이 health 하나라 가장 흔한 실수다")
    void absentExposure_failsStartup() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8091", withExposure(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prometheus 가 없다");
    }

    @Test
    @DisplayName("와일드카드도 통과한다 — 좁히는 것은 액추에이터 체인의 denyAll 이 맡는다")
    void wildcardExposure_passes() {
        assertThat(config.managementPortMustDiffer(8080, "8091", withExposure("*"))).isNotNull();
    }

    @Test
    @DisplayName("대소문자·공백은 가드를 우회하지 못한다")
    void caseAndWhitespace_doNotBypass() {
        assertThat(config.managementPortMustDiffer(8080, "8091", withExposure("health, PROMETHEUS ")))
                .isNotNull();
    }
}
