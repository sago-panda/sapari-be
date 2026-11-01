package com.sapari.streamingapp.config;

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
 * {@code /actuator/env} 와 {@code /heapdump} 가 <b>인증 없이</b> 열린다.
 * {@code /heapdump} 는 메모리 이미지라 Mongo URI 자격증명과 Redis 비밀번호가 평문으로 들어 있다.
 * {@code /actuator/env} 는 값이 가려진다 — <b>실측</b>(바이트코드):
 * {@code management.endpoint.env.show-values} 기본값이 {@code Show.NEVER} 이고
 * {@code Sanitizer.sanitize} 는 그때 {@code "******"} 를 무조건 돌려준다. 그래서 그쪽이 내보내는
 * 것은 값이 아니라 프로퍼티 이름·소스 전부다. 다만 그 마스킹은 <b>기본값</b>이라
 * {@code show-values} 한 줄로 뒤집히고, 추적되는 템플릿에는 그 키가 없다 — 방어가 코드 밖에 있다는
 * 뜻이라 오히려 코드에서 잠글 이유가 된다. 그 사이에 아무 신호가 없어 아무도 모른 채로 굴러가므로,
 * 조용히 뜨는 대신 시끄럽게 죽는 쪽을 택한다.
 *
 * <p>네트워크가 실제 방어라는 점은 live 와 같다 — 스크레이퍼는 로그인 수단이 없으므로 관리 포트는
 * 클러스터 안에만 둬야 한다. 이 가드는 그 전제가 깨졌을 때 <b>깨진 줄 모르는 상태</b>를 막는 것이다.
 */
@Configuration
public class ObservabilityExposureConfig {

    /**
     * 이 앱이 열어도 되는 엔드포인트. {@code health} 는 프로브용, {@code prometheus} 는 스크레이프용이다.
     * 늘리려면 이 상수를 고쳐야 하고, 그러면 변경이 diff 에 보인다.
     *
     * <p>이 집합은 <b>상한이면서 하한</b>이다 — 둘 다 없어도 되는 것이 아니라 둘 다 있어야 한다.
     * 하나는 파드가 Ready 가 되는 조건이고 하나는 지표가 존재하는 조건이라, 빠진 쪽이 조용히 죽는다.
     */
    private static final Set<String> ALLOWED_ENDPOINTS = Set.of("health", "prometheus");

    private static final String EXPOSURE_KEY = "management.endpoints.web.exposure.include";

    /**
     * 노출을 <b>뒤에서 되돌리는</b> 키.
     *
     * <p>{@code include} 만 보는 가드는 반쪽이다 — {@code exclude} 가 {@code include} 를 이긴다.
     * <b>실측</b>: 이 앱을 {@code include: health,prometheus} + {@code exclude: prometheus} 로 띄우면
     * 부팅은 <b>성공하고</b> 노출된 엔드포인트는 {@code health} 하나다. include 만 읽는 가드는 그
     * 설정을 통과시키고 지표는 조용히 빈다 — 가드를 둔 이유가 바로 그 침묵이었다.
     */
    private static final String EXCLUSION_KEY = "management.endpoints.web.exposure.exclude";

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
     * 노출 목록이 <b>정확히</b> 허용 집합인지, 그리고 그것을 되돌리는 설정이 없는지 본다.
     *
     * <p>{@code Binder} 로 읽는 이유: 이 키는 {@code include: health,prometheus} 로도, YAML 리스트로도
     * 쓸 수 있다. 후자는 프로퍼티 소스에 {@code include[0]} 으로 들어가므로 {@code @Value} 로 기본 키를
     * 읽으면 해결에 실패한다 — 그러면 <b>리스트로 쓴 설정을 이 가드가 못 보고 통과시킨다</b>. 두 모양을
     * 다 받는 쪽이 {@code Binder} 다(실측으로 확인했다).
     *
     * <p><b>세 방향을 다 본다.</b> 위로 넘치면(허용 밖) 무인증 공개가 되고, 아래로 모자라면(필수 누락)
     * 관측과 프로브가 조용히 사라진다. 그리고 {@code exclude} 는 그 둘을 다 통과한 설정을 뒤에서
     * 되돌린다 — 한 방향만 보면 나머지 둘이 열린 문으로 남는다.
     */
    private static void verifyExposure(Environment environment) {
        Set<String> declared = readNormalized(environment, EXPOSURE_KEY);

        Set<String> rejected = new TreeSet<>(declared);
        rejected.removeAll(ALLOWED_ENDPOINTS);
        if (!rejected.isEmpty()) {
            throw new IllegalStateException(
                    EXPOSURE_KEY + " 에 허용되지 않은 값이 있다 — " + rejected + ". 이 앱에는 액추에이터를 "
                            + "막을 시큐리티가 없어서 여기 적은 것이 곧 무인증 공개다. \"*\" 는 "
                            + "/heapdump 까지 여는데 그건 메모리 이미지라 Mongo URI 자격증명과 Redis "
                            + "비밀번호가 평문으로 들어 있다. /actuator/env 는 값이 가려지지만(기본값 "
                            + "show-values=NEVER) 프로퍼티 이름과 소스 전부를 내보내고, 그 마스킹은 "
                            + "설정 한 줄로 뒤집힌다. 허용: " + new TreeSet<>(ALLOWED_ENDPOINTS));
        }

        // 허용 밖을 막는 것만으로는 반쪽이다 — 목록이 비면 거부할 것도 없어 통과하는데, 그때
        // 액추에이터 기본 노출은 health 하나라 /actuator/prometheus 가 아예 생기지 않는다. 이 앱에는
        // 시큐리티가 없어 401 조차 나지 않고 404 다 — 어느 쪽이든 지표가 조용히 빈다. 빈 대시보드는
        // "방송이 없어서" 와 구분되지 않는다.
        //
        // health 도 같은 무게로 요구한다. 실측: include 에 prometheus 만 적으면 부팅은 성공하고
        // 노출은 prometheus 하나다 — k8s liveness/readiness 가 404 를 받아 파드가 Ready 가 되지
        // 못하거나 되살아나기를 반복한다. 전에는 prometheus 만 보고 있어 이쪽이 열려 있었다.
        Set<String> missing = new TreeSet<>(ALLOWED_ENDPOINTS);
        missing.removeAll(declared);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    EXPOSURE_KEY + " 에 " + missing + " 가 없다 — health 가 빠지면 k8s 프로브가 404 를 "
                            + "받아 파드가 Ready 가 되지 못하고, prometheus 가 빠지면 chat 지표가 조용히 "
                            + "빈다. 관측이나 프로브를 끄려면 이 가드를 지우는 변경이 리뷰에 보여야 한다. "
                            + "현재 값=" + declared);
        }

        // include 를 통과한 설정을 exclude 가 되돌린다(실측: 둘 다 적으면 exclude 가 이긴다).
        // "*" 는 전부 되돌리므로 필수 둘을 다 지운 것과 같다.
        Set<String> excluded = readNormalized(environment, EXCLUSION_KEY);
        Set<String> suppressed = new TreeSet<>(excluded);
        suppressed.retainAll(ALLOWED_ENDPOINTS);
        if (excluded.contains("*")) {
            suppressed.addAll(ALLOWED_ENDPOINTS);
        }
        if (!suppressed.isEmpty()) {
            throw new IllegalStateException(
                    EXCLUSION_KEY + " 가 " + suppressed + " 를 되돌린다 — " + EXPOSURE_KEY + " 에 적혀 "
                            + "있어도 exclude 가 이기므로 그 엔드포인트는 생기지 않는다. 부팅은 성공하고 "
                            + "지표·프로브만 사라지는, 이 가드가 배제하려는 바로 그 침묵이다. "
                            + "현재 값=" + excluded);
        }
    }

    /** 키 하나를 읽어 소문자·공백 제거로 정규화한다 — 표기 차이로 가드를 비껴가지 못하게 한다. */
    private static Set<String> readNormalized(Environment environment, String key) {
        Set<String> raw = Binder.get(environment)
                .bind(key, Bindable.setOf(String.class))
                .orElseGet(Set::of);
        Set<String> normalized = new TreeSet<>();
        for (String value : raw) {
            String trimmed = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    /** 위 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ObservabilityGuard {
    }
}
