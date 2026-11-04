package com.sapari.liveapp.config;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 관리 포트 분리와 <b>실제 노출</b>을 부팅 시점에 검증하는 두 가드의 테스트.
 *
 * <p>포트 가드가 필요한 이유는 {@code actuatorFilterChain} 이 액추에이터를 {@code permitAll} 하기
 * 때문이다(스크레이퍼는 로그인할 수 없다). 그 개방은 "관리 포트가 사용자 포트와 다르다" 는 전제
 * 위에서만 안전한데, 전제가 설정 한 줄로 깨질 수 있고 깨져도 앱은 멀쩡히 뜬다 — 영업 지표가
 * 사용자 포트에 열린 채로. 그래서 주석이 아니라 부팅 실패로 막는다.
 *
 * <p>노출 가드는 그 반대 방향을 지킨다: 포트만 나뉘고 엔드포인트가 없으면 스크레이퍼와 프로브가
 * 401 을 받아 <b>지표가 조용히 빈다</b>. 두 실패는 방향만 반대인 같은 실패다.
 *
 * <p><b>노출 가드는 설정 키를 읽지 않으므로 이 파일에도 키 표기 테스트가 없다.</b> 전에는
 * {@code include} 스칼라·YAML 리스트 두 표기와 {@code exclude} 되돌림을 각각 재야 했는데,
 * 실측해 보니 그 두 키를 다 읽어도 엔드포인트를 없애는 축이 넷 더 있었다
 * ({@code endpoint.&lt;id&gt;.enabled} · {@code enabled-by-default} · {@code endpoint.&lt;id&gt;.access}
 * · {@code access.default}). 그래서 질문을 "<b>무엇이 실제로 열려 있나</b>" 로 바꿨고, 그러면
 * 표기와 축이 통째로 사라진다.
 */
class ManagementPortGuardTest {

    private final LiveSecurityConfig config = new LiveSecurityConfig();

    // ---------- 관리 포트 ----------

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

    // ---------- 실제 노출 ----------

    @Test
    @DisplayName("필수 둘이 열려 있으면 통과한다")
    void theRequiredPairPasses() {
        assertThatCode(() -> LiveSecurityConfig.verifyExposed(Set.of("health", "prometheus")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("더 열려 있어도 통과한다 — 좁히는 것은 액추에이터 체인의 denyAll 이 맡는다")
    void extraEndpointsPass() {
        // 이 앱은 streaming-app 과 반대다. 여기는 체인이 health·prometheus 밖을 denyAll 로 막으므로
        // 넓어져도 열리지 않는다 — 그 체인이 실제로 막는지는 ActuatorNotOnUserPortTest 가 env 를
        // 일부러 노출해 403 으로 밟는다. 그 단언이 없으면 이 관용이 근거를 잃는다.
        assertThatCode(() -> LiveSecurityConfig.verifyExposed(
                Set.of("beans", "env", "health", "heapdump", "prometheus"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ prometheus 가 실제로 노출되지 않으면 실패시킨다 — 스크레이퍼가 401 을 받고 지표가 조용히 빈다")
    void aMissingPrometheus_failsStartup() {
        // 실측: management.endpoint.prometheus.enabled=false 와 .access=none 이 각각 이 상태를 만들고
        // 둘 다 부팅에 성공했다. 키를 읽던 가드는 둘 다 통과시켰다.
        assertThatThrownBy(() -> LiveSecurityConfig.verifyExposed(Set.of("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[prometheus] 가 실제로 노출되지 않았다");
    }

    @Test
    @DisplayName("⭐ health 가 실제로 노출되지 않으면 실패시킨다 — k8s 프로브가 401 을 받아 파드가 Ready 가 못 된다")
    void aMissingHealth_failsStartup() {
        assertThatThrownBy(() -> LiveSecurityConfig.verifyExposed(Set.of("prometheus")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[health] 가 실제로 노출되지 않았다");
    }

    @Test
    @DisplayName("⭐ 하나도 안 열렸으면 실패시킨다 — enabled-by-default=false 가 부팅에 성공하며 만드는 상태다")
    void anEmptyExposure_failsStartup() {
        // 실측: management.endpoints.enabled-by-default=false 와 access.default=none 이 각각
        // 노출 []를 만들고 둘 다 부팅에 성공했다 — 이 가드가 존재하는 이유 그 자체다.
        assertThatThrownBy(() -> LiveSecurityConfig.verifyExposed(Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[health, prometheus] 가 실제로 노출되지 않았다");
    }

    @Test
    @DisplayName("실패 메시지가 볼 키 여섯을 다 적는다 — 가드는 더 이상 키를 읽지 않는다")
    void theFailureNamesEveryConfigAxis() {
        assertThatThrownBy(() -> LiveSecurityConfig.verifyExposed(Set.of()))
                .hasMessageContaining("exposure.include/exclude")
                .hasMessageContaining("management.endpoint.<id>.enabled")
                .hasMessageContaining("management.endpoints.enabled-by-default")
                .hasMessageContaining("management.endpoint.<id>.access")
                .hasMessageContaining("management.endpoints.access.default");
    }

    @Test
    @DisplayName("⭐ WebEndpointsSupplier 의 엔드포인트 id 를 그대로 읽는다 — 이 다리가 틀리면 전부 무의미하다")
    void theExposedIdsComeFromTheSupplier() {
        ExposableWebEndpoint health = mock(ExposableWebEndpoint.class);
        given(health.getEndpointId()).willReturn(EndpointId.of("health"));
        ExposableWebEndpoint prometheus = mock(ExposableWebEndpoint.class);
        given(prometheus.getEndpointId()).willReturn(EndpointId.of("prometheus"));
        WebEndpointsSupplier supplier = mock(WebEndpointsSupplier.class);
        given(supplier.getEndpoints()).willReturn(List.of(prometheus, health));

        assertThat(LiveSecurityConfig.exposedIds(supplier)).containsExactly("health", "prometheus");
    }

    @Test
    @DisplayName("액추에이터가 없는 컨텍스트에서는 건너뛴다 — 이 설정을 올리는 MVC 조각 테스트가 깨지지 않게")
    void theCheckIsSkippedWithoutActuator() {
        @SuppressWarnings("unchecked")
        ObjectProvider<WebEndpointsSupplier> absent = mock(ObjectProvider.class);
        given(absent.getIfAvailable()).willReturn(null);

        assertThatCode(() -> config.requiredEndpointsMustBeExposed(absent)).doesNotThrowAnyException();
    }

    /** 주어진 id 들을 노출한다고 답하는 공급자. */
    private static WebEndpointsSupplier supplierOf(String... ids) {
        List<ExposableWebEndpoint> endpoints = java.util.Arrays.stream(ids).map(id -> {
            ExposableWebEndpoint endpoint = mock(ExposableWebEndpoint.class);
            given(endpoint.getEndpointId()).willReturn(EndpointId.of(id));
            return endpoint;
        }).toList();
        WebEndpointsSupplier supplier = mock(WebEndpointsSupplier.class);
        given(supplier.getEndpoints()).willReturn(endpoints);
        return supplier;
    }

    private static ObjectProvider<WebEndpointsSupplier> providing(WebEndpointsSupplier supplier) {
        @SuppressWarnings("unchecked")
        ObjectProvider<WebEndpointsSupplier> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(supplier);
        return provider;
    }

    @Test
    @DisplayName("⭐ 빈이 공급자를 받으면 실제로 검사한다 — 이 단언이 없으면 가드를 떼도 전부 초록이다")
    void theBeanActuallyChecksWhenASupplierIsPresent() {
        // 검사를 호출하는 네 줄(getIfAvailable → null 아니면 verifyExposed)에 단언이 없었다.
        // 실측: 그 네 줄만 지우면 이 앱 113건이 전부 초록이었다 — 집합 산술 단위 테스트는
        // verifyExposed 를 직접 부르므로 배선이 빠진 것을 보지 못한다.
        assertThatThrownBy(() -> config.requiredEndpointsMustBeExposed(providing(supplierOf("health"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[prometheus] 가 실제로 노출되지 않았다");
    }

    @Test
    @DisplayName("빈이 올바른 노출을 통과시킨다 — 거부만 재면 \"항상 던진다\" 도 통과한다")
    void theBeanPassesAValidExposure() {
        assertThatCode(() -> config.requiredEndpointsMustBeExposed(
                providing(supplierOf("health", "prometheus")))).doesNotThrowAnyException();
    }
}
