package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;

/**
 * 관측 가드 — 포트와 <b>실제 노출</b>.
 *
 * <p>노출 검사가 설정 키를 읽지 않으므로 이 파일에도 키 표기 테스트가 없다. 전에는
 * {@code include: a,b} 와 YAML 리스트({@code include[0]}) 두 표기를 각각 재야 했는데, 파싱을 버리면서
 * 그 축이 통째로 사라졌다 — <b>실측</b>으로 드러난 것은 그 두 키를 다 읽어도 엔드포인트를 없애는
 * 축이 넷 더 있다는 것이었고({@code endpoint.&lt;id&gt;.enabled} · {@code enabled-by-default} ·
 * {@code endpoint.&lt;id&gt;.access} · {@code access.default}), 그래서 질문을 "설정이 무엇이라고
 * 적혀 있나" 에서 "<b>무엇이 실제로 열려 있나</b>" 로 바꿨다.
 */
@DisplayName("ObservabilityExposureConfig — 포트는 설정으로, 노출은 실제로 본다")
class ObservabilityExposureConfigTest {

    private final ObservabilityExposureConfig config = new ObservabilityExposureConfig();

    // ---------- 관리 포트 ----------

    @Test
    @DisplayName("포트가 다르면 통과한다")
    void differentPortsPass() {
        assertThatCode(() -> config.managementPortMustDiffer(8080, "8092")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ 관리 포트가 비어 있으면 부팅을 막는다 — 액추에이터가 WS 포트에 열린다")
    void anEmptyManagementPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("management.server.port 가 없다");
    }

    @Test
    @DisplayName("⭐ 사용자 포트와 같으면 막는다 — 이 앱에는 그걸 막을 시큐리티가 없다")
    void anIdenticalPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "8080"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.port 와 같다");
    }

    @Test
    @DisplayName("⭐ 음수(-1)는 막는다 — 액추에이터가 통째로 꺼져 지표가 조용히 사라진다")
    void aDisabledManagementPortIsRejected() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("음수");
    }

    @Test
    @DisplayName("숫자가 아니면 그 값을 담아 막는다 — 원인이 오타라는 게 로그에서 읽혀야 한다")
    void aNonNumericPortIsRejectedWithItsValue() {
        assertThatThrownBy(() -> config.managementPortMustDiffer(8080, "팔공구이"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("팔공구이");
    }

    @Test
    @DisplayName("둘 다 0(랜덤 포트)이면 통과한다 — 실행 시점에 서로 다른 포트가 잡힌다")
    void bothRandomPortsAreAllowed() {
        assertThatCode(() -> config.managementPortMustDiffer(0, "0")).doesNotThrowAnyException();
    }

    // ---------- 실제 노출 ----------

    @Test
    @DisplayName("필수 둘만 열려 있으면 통과한다")
    void exactlyTheRequiredPairPasses() {
        assertThatCode(() -> ObservabilityExposureConfig.verifyExposure(Set.of("health", "prometheus")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("⭐ prometheus 가 사라지면 막는다 — 지표가 조용히 빈다")
    void aMissingPrometheusIsRejected() {
        // 실측: management.endpoint.prometheus.enabled=false 와 .access=none 이 각각 이 상태를 만들고
        // 둘 다 부팅에 성공했다. 키를 읽는 가드는 둘 다 통과시켰다.
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(Set.of("health")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사라진 것: [prometheus]");
    }

    @Test
    @DisplayName("⭐ health 가 사라지면 막는다 — 프로브가 404 를 받아 파드가 Ready 가 못 된다")
    void aMissingHealthIsRejected() {
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(Set.of("prometheus")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사라진 것: [health]");
    }

    @Test
    @DisplayName("⭐ 하나도 안 열렸으면 막는다 — enabled-by-default=false 가 부팅에 성공하며 만드는 상태다")
    void anEmptyExposureIsRejected() {
        // 실측: management.endpoints.enabled-by-default=false 와 access.default=none 이 각각
        // 노출 []를 만들고 둘 다 부팅에 성공했다 — 이 가드가 존재하는 이유 그 자체다.
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사라진 것: [health, prometheus]");
    }

    @Test
    @DisplayName("⭐ 하나라도 더 열렸으면 막는다 — 시큐리티가 없어 열린 것이 곧 무인증 공개다")
    void anExtraEndpointIsRejectedWithItsName() {
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(
                Set.of("health", "prometheus", "env")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("더 열린 것: [env]");
    }

    @Test
    @DisplayName("⭐ 전부 열린 상태는 넘친 것을 전부 이름으로 말한다")
    void aWideOpenExposureNamesEveryExtra() {
        // 실측한 include="*" 의 노출 13종 중 일부다. heapdump 는 그 13종에 들지 않는다
        // (@WebEndpoint defaultAccess=NONE — include 만으로는 생기지 않고 실측에서 401).
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(
                Set.of("beans", "configprops", "env", "health", "prometheus", "threaddump")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("더 열린 것: [beans, configprops, env, threaddump]")
                .hasMessageContaining("/actuator/env 는 프로퍼티 이름과 소스 전부를 내보낸다");
    }

    @Test
    @DisplayName("실패 메시지가 볼 키 여섯을 다 적는다 — 가드는 더 이상 키를 읽지 않는다")
    void theFailureNamesEveryConfigAxis() {
        assertThatThrownBy(() -> ObservabilityExposureConfig.verifyExposure(Set.of()))
                .hasMessageContaining("exposure.include/exclude")
                .hasMessageContaining("management.endpoint.<id>.enabled")
                .hasMessageContaining("management.endpoints.enabled-by-default")
                .hasMessageContaining("management.endpoint.<id>.access")
                .hasMessageContaining("management.endpoints.access.default");
    }

    // ---------- 실제 API 와의 다리 ----------

    @Test
    @DisplayName("⭐ WebEndpointsSupplier 의 엔드포인트 id 를 그대로 읽는다 — 이 다리가 틀리면 전부 무의미하다")
    void theExposedIdsComeFromTheSupplier() {
        ExposableWebEndpoint health = mock(ExposableWebEndpoint.class);
        given(health.getEndpointId()).willReturn(EndpointId.of("health"));
        ExposableWebEndpoint prometheus = mock(ExposableWebEndpoint.class);
        given(prometheus.getEndpointId()).willReturn(EndpointId.of("prometheus"));
        WebEndpointsSupplier supplier = mock(WebEndpointsSupplier.class);
        given(supplier.getEndpoints()).willReturn(List.of(prometheus, health));

        assertThat(ObservabilityExposureConfig.exposedIds(supplier))
                .containsExactly("health", "prometheus");
    }

    @Test
    @DisplayName("액추에이터가 없는 컨텍스트에서는 건너뛴다 — 조각 테스트가 없는 빈을 요구하지 않게")
    void theCheckIsSkippedWithoutActuator() {
        @SuppressWarnings("unchecked")
        ObjectProvider<WebEndpointsSupplier> absent = mock(ObjectProvider.class);
        given(absent.getIfAvailable()).willReturn(null);

        assertThatCode(() -> config.exposedEndpointsMustMatchRequired(absent)).doesNotThrowAnyException();
    }

    // ---------- 빈 배선 ----------

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
        // 검사를 호출하는 네 줄(getIfAvailable → null 아니면 verifyExposure)에 단언이 없었다.
        // 그래서 그 네 줄만 지우면 가드가 통째로 무장해제되는데 이 앱의 테스트가 전부 초록이었다 —
        // 집합 산술 단위 테스트는 verifyExposure 를 직접 부르므로 배선이 빠진 것을 보지 못한다.
        assertThatThrownBy(() -> config.exposedEndpointsMustMatchRequired(providing(supplierOf("health"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("사라진 것: [prometheus]");
    }

    @Test
    @DisplayName("빈이 올바른 노출을 통과시킨다 — 거부만 재면 \"항상 던진다\" 도 통과한다")
    void theBeanPassesAValidExposure() {
        assertThatCode(() -> config.exposedEndpointsMustMatchRequired(
                providing(supplierOf("health", "prometheus")))).doesNotThrowAnyException();
    }

    // ---------- 가드의 하중 지점: getEndpoints() 가 설정 축을 반영하는가 ----------

    /**
     * 액추에이터 자동설정만 올린 최소 컨텍스트 — 이 가드가 기대는 <b>단 하나의 사실</b>을 잰다.
     *
     * <p>가드는 "설정 축이 엔드포인트를 없애면 {@code getEndpoints()} 가 그걸 보여 준다" 에 전부를
     * 걸고 있다. 그 사실이 틀리면(예: Boot 가 "엔드포인트는 남고 operation 만 0" 으로 바뀌면)
     * 가드는 조용히 통과하고 지표만 빈다 — 전 스위트가 초록인 채로. 그래서 그 하나를 고정한다.
     *
     * <p>관측 대상으로 {@code info} 를 쓰는 이유는 가볍고 외부 의존이 없기 때문이다. 앱을 띄우지
     * 않으므로 컨테이너도 필요 없다.
     */
    private final ReactiveWebApplicationContextRunner actuatorOnly = new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    EndpointAutoConfiguration.class,
                    WebEndpointAutoConfiguration.class,
                    InfoEndpointAutoConfiguration.class));

    @Test
    @DisplayName("⭐ include 가 노출을 만든다 — 기준선")
    void theIncludeKeyProducesTheExposure() {
        actuatorOnly.withPropertyValues("management.endpoints.web.exposure.include=info")
                .run(context -> assertThat(ObservabilityExposureConfig.exposedIds(
                        context.getBean(WebEndpointsSupplier.class))).containsExactly("info"));
    }

    @Test
    @DisplayName("⭐ endpoint.<id>.enabled=false 가 노출을 지우고 getEndpoints() 가 그걸 보여 준다")
    void theEnabledAxisRemovesTheExposure() {
        actuatorOnly.withPropertyValues("management.endpoints.web.exposure.include=info",
                        "management.endpoint.info.enabled=false")
                .run(context -> assertThat(ObservabilityExposureConfig.exposedIds(
                        context.getBean(WebEndpointsSupplier.class))).isEmpty());
    }

    @Test
    @DisplayName("⭐ enabled-by-default=false 도 같다 — include 에 적혀 있어도 사라진다")
    void theEnabledByDefaultAxisRemovesTheExposure() {
        // 이 축이 가장 위험하다: include 를 제대로 적은 설정이 엔드포인트 0개로 부팅한다.
        actuatorOnly.withPropertyValues("management.endpoints.web.exposure.include=info",
                        "management.endpoints.enabled-by-default=false")
                .run(context -> assertThat(ObservabilityExposureConfig.exposedIds(
                        context.getBean(WebEndpointsSupplier.class))).isEmpty());
    }

    @Test
    @DisplayName("⭐ exclude 도 같다 — 파싱을 버렸어도 이 축은 여전히 노출을 지운다")
    void theExcludeAxisRemovesTheExposure() {
        actuatorOnly.withPropertyValues("management.endpoints.web.exposure.include=info",
                        "management.endpoints.web.exposure.exclude=info")
                .run(context -> assertThat(ObservabilityExposureConfig.exposedIds(
                        context.getBean(WebEndpointsSupplier.class))).isEmpty());
    }

    /*
     * ⚠️ access 두 축({@code endpoint.<id>.access=none}, {@code endpoints.access.default=none})은 이
     * 러너로 고정하지 못했다. 접근을 끄면 @ConditionalOnAvailableEndpoint 가 그 엔드포인트 빈을
     * 없애고, 엔드포인트가 그것뿐인 최소 컨텍스트는 아예 뜨지 못한다(실측). 대신 <b>실제 앱으로</b>
     * 쟀고 결과는 위 축들과 동형이다:
     *
     *   management.endpoint.prometheus.access=none   부팅 성공   노출 [health]
     *   management.endpoints.access.default=none     부팅 성공   노출 []
     *
     * 즉 그 두 축도 getEndpoints() 에 그대로 보인다. 앱을 띄우는 가드 테스트를 따로 만들면 고정할
     * 수 있고, 그건 컨테이너 한 벌을 더 쓰는 선택이라 여기 적어만 둔다.
     */
}
