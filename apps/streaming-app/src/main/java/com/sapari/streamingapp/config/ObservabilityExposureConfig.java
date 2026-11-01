package com.sapari.streamingapp.config;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 관측 엔드포인트를 <b>어디에 열고 무엇만 열지</b>를 부팅 시점에 강제한다.
 *
 * <p>이 앱에는 Spring Security 가 없다(확인: {@code SecurityWebFilterChain} 0건). live-app 은
 * 액추에이터 전용 필터체인에서 두 엔드포인트만 {@code permitAll} 하고 나머지를 {@code denyAll} 로
 * 막는데, 여기엔 그 체인을 걸 자리가 없다 — 즉 <b>설정이 유일한 관문</b>이다.
 *
 * <p>그 설정이 틀리면 결과가 설정 실수의 수준을 넘는다. 전부 열리는 순간
 * {@code /actuator/env} 와 {@code /heapdump} 가 <b>인증 없이</b> 열린다.
 * {@code /heapdump} 는 메모리 이미지라 Mongo URI 자격증명과 Redis 비밀번호가 평문으로 들어 있다.
 * {@code /actuator/env} 는 값이 가려진다 — <b>실측</b>(바이트코드):
 * {@code management.endpoint.env.show-values} 기본값이 {@code Show.NEVER} 이고
 * {@code Sanitizer.sanitize} 는 그때 {@code "******"} 를 무조건 돌려준다. 그래서 그쪽이 내보내는
 * 것은 값이 아니라 프로퍼티 이름·소스 전부다. 다만 그 마스킹은 <b>기본값</b>이라
 * {@code show-values} 한 줄로 뒤집히고, 추적되는 템플릿에는 그 키가 없다 — 방어가 코드 밖에 있다는
 * 뜻이라 오히려 코드에서 잠글 이유가 된다.
 *
 * <p>반대 방향도 같은 무게다. 엔드포인트가 조용히 <b>사라지면</b> 스크레이퍼와 k8s 프로브가 404 를
 * 받고, 부팅은 성공하므로 아무 신호가 없다. 빈 대시보드는 "방송이 없어서" 와 구분되지 않는다.
 * 그래서 이 가드는 양방향이다 — 넘치는 것과 모자라는 것을 같이 막는다.
 *
 * <p>네트워크가 실제 방어라는 점은 live 와 같다 — 스크레이퍼는 로그인 수단이 없으므로 관리 포트는
 * 클러스터 안에만 둬야 한다. 이 가드는 그 전제가 깨졌을 때 <b>깨진 줄 모르는 상태</b>를 막는 것이다.
 */
@Configuration
public class ObservabilityExposureConfig {

    /**
     * 열려야 하는 엔드포인트 — 그리고 <b>이것만</b> 열려야 한다.
     *
     * <p>{@code health} 는 k8s 프로브용, {@code prometheus} 는 스크레이프용이다. 이 집합은
     * <b>상한이면서 하한</b>이다: 하나는 파드가 Ready 가 되는 조건이고 하나는 지표가 존재하는
     * 조건이라 빠진 쪽이 조용히 죽고, 더 열린 쪽은 시큐리티가 없어 곧 무인증 공개다. 늘리려면 이
     * 상수를 고쳐야 하고, 그러면 변경이 diff 에 보인다.
     */
    private static final Set<String> REQUIRED_ENDPOINTS = Set.of("health", "prometheus");

    /**
     * 관리 포트가 사용자 포트와 같으면 <b>부팅을 실패시킨다</b>.
     *
     * <p>사용자 포트는 WS 핸드셰이크가 들어오는 포트다. 두 포트가 같아지면 지표가 그 포트에 그대로
     * 노출되고, 이 앱에는 그걸 막을 시큐리티가 없다. {@code /actuator/prometheus} 는 영업 물량
     * (방 수·시청자 수·전송량)을 담는다.
     */
    @Bean
    public ObservabilityGuard managementPortMustDiffer(
            @Value("${server.port:8080}") int serverPort,
            @Value("${management.server.port:}") String managementPort) {

        if (managementPort == null || managementPort.isBlank()) {
            throw new IllegalStateException(
                    "management.server.port 가 없다 — 액추에이터가 WS 핸드셰이크를 받는 사용자 포트에 "
                            + "열린다. application.yml 의 management.server.port 를 채우거나 "
                            + "MANAGEMENT_PORT 를 주입할 것"
                            + "(예시: apps/streaming-app/src/main/resources/application.yml.example).");
        }

        int port;
        try {
            port = Integer.parseInt(managementPort.trim());
        } catch (NumberFormatException e) {
            // 친절한 실패가 이 가드의 목적이다 — NumberFormatException 스택트레이스로 죽으면 원인이
            // "포트 설정 오타"라는 게 로그에서 바로 읽히지 않는다.
            throw new IllegalStateException(
                    "management.server.port 가 숫자가 아니다 — 값=" + managementPort.trim(), e);
        }
        if (port < 0) {
            // -1 은 "액추에이터를 통째로 끈다" 는 뜻이다. 부팅은 되고 지표만 조용히 사라지는데,
            // 이 가드가 배제하려는 실패가 정확히 그것이다(관측이 없는 걸 아무도 모르는 상태).
            throw new IllegalStateException(
                    "management.server.port(" + port + ") 가 음수다 — 액추에이터가 비활성화되어 지표가 "
                            + "사라진다. 관측을 끄려면 이 가드를 지우는 변경이 리뷰에 보여야 한다.");
        }
        // 0 은 포트 번호가 아니라 "임의의 빈 포트를 골라라" 라는 지시다. 둘 다 0 이면 실행 시점에
        // 서로 다른 포트가 잡히므로 같은 포트가 아니다 — 숫자만 비교하면 통합 테스트(RANDOM_PORT)가
        // 부팅조차 못 한다. 운영 설정에는 0 이 오지 않으므로 이 완화가 실제 방어를 약화시키지 않는다.
        if (port != 0 && serverPort != 0 && port == serverPort) {
            throw new IllegalStateException(
                    "management.server.port(" + port + ") 가 server.port 와 같다 — 지표가 사용자 "
                            + "트래픽 포트에 노출되고 이 앱에는 그걸 막을 시큐리티가 없다. "
                            + "MANAGEMENT_PORT 를 다른 값으로 줄 것.");
        }

        return new ObservabilityGuard();
    }

    /**
     * <b>실제로 노출된</b> 엔드포인트가 필수 집합과 정확히 같아야 부팅한다.
     *
     * <p>⚠️ <b>설정 키를 읽지 않는다.</b> 전에는 {@code exposure.include} 와
     * {@code exposure.exclude} 두 키를 파싱했는데, 엔드포인트를 조용히 없애는 축이 그 둘뿐이 아니다.
     * <b>실측</b>(이 앱을 실제로 띄워 {@code WebEndpointsSupplier} 를 조회):
     * <pre>
     * management.endpoint.prometheus.enabled=false    부팅 성공   노출 [health]
     * management.endpoint.health.enabled=false        부팅 성공   노출 [prometheus]
     * management.endpoints.enabled-by-default=false   부팅 성공   노출 []
     * management.endpoint.prometheus.access=none      부팅 성공   노출 [health]
     * management.endpoints.access.default=none        부팅 성공   노출 []
     * </pre>
     * 다섯 줄 모두 두 키만 보는 가드를 <b>통과했다</b>. 특히 셋째·다섯째는 엔드포인트가 하나도
     * 없는데 부팅에 성공한다 — 이 가드가 존재하는 이유 그 자체다. 축을 더 열거하는 길은 끝이 없어서
     * (Boot 가 축을 하나 더 만들면 또 새는) <b>실제 노출을 묻는 쪽</b>으로 바꿨다.
     *
     * <p>{@code WebEndpointsSupplier.getEndpoints()} 가 그 단일 출처다 — 위 다섯 조합과
     * {@code include}/{@code exclude} 조합 모두에서 실제 노출과 일치했다(실측). 덤으로 YAML 리스트
     * 표기({@code include[0]})를 따로 다룰 이유도 사라진다: 파싱을 안 하므로 표기 차이가 없다.
     *
     * <p>{@code ObjectProvider} 로 받는 이유: 액추에이터가 없는 컨텍스트에서는 이 검사를 건너뛴다.
     * 이 앱에 액추에이터는 {@code implementation} 의존이라 운영에서는 항상 있고, 빼는 변경은
     * {@code build.gradle} diff 에 보인다. 조각 테스트가 이 설정만 올릴 때 없는 빈을 요구해
     * 엉뚱하게 깨지는 쪽을 막으려는 것이다.
     *
     * <p>⚠️ <b>그 건너뛰기가 운영 구성에서 일어나지 않는지 따로 쟀다.</b> 관리 포트를 사용자 포트와
     * 다르게 주면 Boot 는 관리 전용 <b>자식 컨텍스트</b>를 만든다 — {@code WebEndpointsSupplier} 가
     * 그 자식에만 있다면 부모에서 도는 이 가드는 {@code null} 을 받아 정확히 운영 구성에서만 조용히
     * 통과했을 것이다. <b>실측</b>(live-app 에서 실제 서버 둘, 관리 53154 / 사용자 53151 — 자식
     * 컨텍스트가 생긴 구성): 부모의 그 빈 <b>1건</b>, 나쁜 설정은 가드 메시지로 컨텍스트 로드 실패.
     * 두 앱이 같은 Boot 배선을 쓰므로 결론은 같다 — 건너뛰기는 액추에이터가 아예 없을 때만이다.
     */
    @Bean
    public ExposureGuard exposedEndpointsMustMatchRequired(
            ObjectProvider<WebEndpointsSupplier> webEndpoints) {

        WebEndpointsSupplier supplier = webEndpoints.getIfAvailable();
        if (supplier != null) {
            verifyExposure(exposedIds(supplier));
        }
        return new ExposureGuard();
    }

    /** 실제로 노출된 web 엔드포인트 id 들. 정렬해 둬서 실패 메시지가 안정적으로 읽힌다. */
    static Set<String> exposedIds(WebEndpointsSupplier supplier) {
        Set<String> ids = new TreeSet<>();
        supplier.getEndpoints().forEach(endpoint -> ids.add(endpoint.getEndpointId().toString()));
        return ids;
    }

    /**
     * 노출 집합을 필수 집합과 비교한다 — 넘치는 것과 모자라는 것을 따로 말해 준다.
     *
     * <p>스프링 없이 호출할 수 있게 집합만 받는다(빈 배선은 위에서 한다). 실패 메시지에 <b>어떤 키를
     * 볼지</b>를 적는 이유는 이 가드가 더는 키를 읽지 않기 때문이다 — 원인이 되는 축이 여섯이고
     * 운영자가 {@code access.default} 같은 건 알지 못한다.
     */
    static void verifyExposure(Set<String> actual) {
        Set<String> extra = new TreeSet<>(actual);
        extra.removeAll(REQUIRED_ENDPOINTS);
        Set<String> missing = new TreeSet<>(REQUIRED_ENDPOINTS);
        missing.removeAll(actual);
        if (extra.isEmpty() && missing.isEmpty()) {
            return;
        }

        StringBuilder message = new StringBuilder("실제로 노출된 액추에이터 엔드포인트가 "
                + new TreeSet<>(REQUIRED_ENDPOINTS) + " 와 다르다 — 현재 " + actual + ".");
        if (!extra.isEmpty()) {
            message.append(" 더 열린 것: ").append(extra)
                    .append(". 이 앱에는 액추에이터를 막을 시큐리티가 없어서 열린 것이 곧 무인증")
                    .append(" 공개다 — /heapdump 는 메모리 이미지라 Mongo URI 자격증명과 Redis")
                    .append(" 비밀번호가 평문으로 들어 있다.");
        }
        if (!missing.isEmpty()) {
            message.append(" 사라진 것: ").append(missing)
                    .append(". health 가 없으면 k8s 프로브가 404 를 받아 파드가 Ready 가 되지 못하고,")
                    .append(" prometheus 가 없으면 chat 지표가 조용히 빈다.");
        }
        message.append(" 볼 키: management.endpoints.web.exposure.include/exclude,")
                .append(" management.endpoint.<id>.enabled, management.endpoints.enabled-by-default,")
                .append(" management.endpoint.<id>.access, management.endpoints.access.default.");
        throw new IllegalStateException(message.toString());
    }

    /** 포트 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ObservabilityGuard {
    }

    /** 노출 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ExposureGuard {
    }
}
