package com.sapari.streamingapp.config;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 관측 엔드포인트를 <b>어디에 열고 무엇만 열지</b>를 부팅 시점에 강제한다.
 *
 * <p>이 앱에는 Spring Security 가 없다(확인: {@code SecurityWebFilterChain} 0건). live-app 은
 * 액추에이터 전용 필터체인에서 두 엔드포인트만 {@code permitAll} 하고 나머지를 {@code denyAll} 로
 * 막는데, 여기엔 그 체인을 걸 자리가 없다 — 즉 <b>yaml 한 줄이 유일한 관문</b>이다.
 *
 * <p>그 한 줄이 틀리면 결과가 설정 실수의 수준을 넘는다. {@code include: "*"} 를 쓰는 순간
 * {@code /actuator/env} 와 {@code /heapdump} 가 <b>인증 없이</b> 열리고, 그 응답에는 룸 토큰
 * 공개키 설정은 물론 Mongo URI 의 자격증명과 Redis 비밀번호가 담긴다. 그 사이에 아무 신호가 없어
 * 아무도 모른 채로 굴러가므로, 조용히 뜨는 대신 시끄럽게 죽는 쪽을 택한다.
 *
 * <p>네트워크가 실제 방어라는 점은 live 와 같다 — 스크레이퍼는 로그인 수단이 없으므로 관리 포트는
 * 클러스터 안에만 둬야 한다. 이 가드는 그 전제가 깨졌을 때 <b>깨진 줄 모르는 상태</b>를 막는 것이다.
 */
@Configuration
public class ObservabilityExposureConfig {

    /**
     * 이 앱이 열어도 되는 엔드포인트. {@code health} 는 프로브용, {@code prometheus} 는 스크레이프용이다.
     * 늘리려면 이 상수를 고쳐야 하고, 그러면 변경이 diff 에 보인다.
     */
    private static final Set<String> ALLOWED_ENDPOINTS = Set.of("health", "prometheus");

    private static final String EXPOSURE_KEY = "management.endpoints.web.exposure.include";

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
            @Value("${management.server.port:}") String managementPort,
            Environment environment) {

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

        verifyExposure(environment);
        return new ObservabilityGuard();
    }

    /**
     * 노출 목록이 허용 집합 안인지 본다.
     *
     * <p>{@code Binder} 로 읽는 이유: 이 키는 {@code include: health,prometheus} 로도,
     * YAML 리스트로도 쓸 수 있다. 후자는 프로퍼티 소스에 {@code include[0]} 으로 들어가므로
     * {@code @Value} 로 기본 키를 읽으면 해결에 실패한다 — 그러면 <b>리스트로 쓴 설정을 이 가드가
     * 못 보고 통과시킨다</b>. 두 모양을 다 받는 쪽이 {@code Binder} 다(실측으로 확인했다).
     */
    private static void verifyExposure(Environment environment) {
        Set<String> declared = Binder.get(environment)
                .bind(EXPOSURE_KEY, Bindable.setOf(String.class))
                .orElseGet(Set::of);

        Set<String> rejected = new TreeSet<>(Comparator.naturalOrder());
        for (String endpoint : declared) {
            String normalized = endpoint == null ? "" : endpoint.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && !ALLOWED_ENDPOINTS.contains(normalized)) {
                rejected.add(normalized);
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalStateException(
                    EXPOSURE_KEY + " 에 허용되지 않은 값이 있다 — " + rejected + ". 이 앱에는 액추에이터를 "
                            + "막을 시큐리티가 없어서 여기 적은 것이 곧 무인증 공개다. \"*\" 는 "
                            + "/actuator/env·/heapdump 까지 열어 Mongo URI 자격증명과 Redis 비밀번호를 "
                            + "노출한다. 허용: " + new TreeSet<>(ALLOWED_ENDPOINTS));
        }

        // 허용 집합 밖을 막는 것만으로는 반쪽이다 — 목록이 비면 거부할 것도 없어 통과하는데, 그때
        // /actuator/prometheus 는 아예 생기지 않는다(액추에이터 기본 노출은 health 하나다). 이 앱에는
        // 시큐리티가 없어 401 조차 나지 않고 404 다 — 어느 쪽이든 <b>지표가 조용히 빈다</b>. 빈
        // 대시보드는 "방송이 없어서" 와 구분되지 않는다. live-app 은 같은 실패를 부팅 실패로 막는데
        // (LiveSecurityConfig.requirePrometheusExposed) 이쪽만 비어 있었다 — 그 비대칭을 없앤다.
        boolean prometheusExposed = declared.stream()
                .map(value -> value == null ? "" : value.trim().toLowerCase(Locale.ROOT))
                .anyMatch("prometheus"::equals);
        if (!prometheusExposed) {
            throw new IllegalStateException(
                    EXPOSURE_KEY + " 에 prometheus 가 없다 — /actuator/prometheus 가 생기지 않아 chat "
                            + "지표가 조용히 빈다. 관측을 끄려면 이 가드를 지우는 변경이 리뷰에 보여야 "
                            + "한다. 현재 값=" + new TreeSet<>(declared));
        }
    }

    /** 위 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ObservabilityGuard {
    }
}
