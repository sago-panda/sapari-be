package com.sapari.liveapp.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("포트가 다르면 통과한다")
    void differentPorts_pass() {
        assertThat(config.managementPortMustDiffer(8080, "8081")).isNotNull();
    }

    @Test
    @DisplayName("포트가 같으면 부팅을 실패시킨다 — 지표가 사용자 트래픽 포트에 노출된다")
    void samePort_failsStartup() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8080"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사용자 트래픽 포트");
    }

    @Test
    @DisplayName("둘 다 0(랜덤 포트)이면 통과한다 — 실행 시점에 서로 다른 포트가 잡히므로 같은 포트가 아니다")
    void bothRandomPorts_pass() {
        assertThat(config.managementPortMustDiffer(0, "0")).isNotNull();
    }

    @Test
    @DisplayName("관리 포트 설정 자체가 없으면 실패시킨다 — 기본값은 사용자 포트와 같다")
    void missingManagementPort_failsStartup() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, ""))
                .isInstanceOf(IllegalStateException.class);
    }
}
