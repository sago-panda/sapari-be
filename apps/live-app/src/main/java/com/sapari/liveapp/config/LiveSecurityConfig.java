package com.sapari.liveapp.config;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;

/**
 * live-app 보안 설정 (stateless).
 *
 * <p>시청(조회)은 공개, 라이브 생성/시작/종료 등 변경은 판매자 전용이다. 홈랩이 발급한 access JWT를
 * {@link StatelessJwtAuthenticationFilter}가 서명·claims만으로 검증하며, user DB·폐기 저장소에
 * 의존하지 않는다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class LiveSecurityConfig {

    @Bean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint(
            ObjectMapper objectMapper,
            TimeProvider timeProvider
    ) {
        return new JwtAuthenticationEntryPoint(objectMapper, timeProvider);
    }

    @Bean
    public JwtAccessDeniedHandler jwtAccessDeniedHandler(
            ObjectMapper objectMapper,
            TimeProvider timeProvider
    ) {
        return new JwtAccessDeniedHandler(objectMapper, timeProvider);
    }

    @Bean
    public StatelessJwtAuthenticationFilter statelessJwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider
    ) {
        return new StatelessJwtAuthenticationFilter(jwtTokenProvider);
    }

    /**
     * 관측 엔드포인트 전용 체인. <b>포트를 나눠도 시큐리티는 따라온다</b> — 관리 포트의 자식 컨텍스트에
     * 같은 필터가 등록되므로, 이 체인이 없으면 아래 {@code anyRequest().authenticated()} 에 걸려
     * 프로메테우스가 401 을 받는다(지표가 조용히 비고, 그게 "방송이 없어서"와 구분되지 않는다).
     *
     * <p>여기서 {@code permitAll} 인 것은 <b>관리 포트가 사용자 포트와 다르다는 전제</b> 위에서만
     * 안전하다. 그 전제는 아래 {@link #managementPortMustDiffer} 가 부팅 시점에 강제한다 —
     * 주석으로만 두면 누가 포트를 합치는 순간 지표가 인터넷에 열린다.
     *
     * <p>스크레이퍼는 로그인 수단이 없으므로 인증을 걸 수 없다. 실제 방어는 네트워크 계층이다
     * (관리 포트를 ClusterIP 로만 두고 인그레스에 태우지 않는다 — {@code infra/AGENTS.md}).
     *
     * <p><b>여는 것은 두 엔드포인트뿐이고 나머지는 코드에서 막는다.</b> 노출 범위를 yaml 한 줄
     * ({@code exposure.include})에만 맡기면 거기에 {@code env} 나 {@code "*"} 를 더하는 순간
     * {@code /actuator/env}·{@code /heapdump} 가 무인증으로 열린다. 둘의 무게가 다르다:
     *
     * <p>{@code /heapdump} 는 <b>메모리 이미지</b>라 마스킹이라는 개념이 없다 — JWT 서명키와 LiveKit
     * API 시크릿이 그 안에 평문으로 있다.
     *
     * <p>{@code /actuator/env} 는 <b>값이 가려진다</b>(실측, 바이트코드):
     * {@code management.endpoint.env.show-values} 기본값이 {@code Show.NEVER} 이고
     * {@code Sanitizer.sanitize} 는 그 경우 {@code SanitizingFunction} 을 보지도 않고
     * {@code "******"} 를 돌려준다. 그래서 이 엔드포인트가 내보내는 것은 시크릿 값이 아니라
     * <b>프로퍼티 이름과 소스 전부</b> — 어떤 키가 있고 어떤 파일·환경변수가 쓰이는지의 설계도다.
     * 한때 이 자리에 "그 응답에 시크릿이 담긴다" 고 적었는데 그건 틀렸다.
     *
     * <p>그런데 그 마스킹이 <b>기본값</b>이다. {@code show-values} 한 줄로 뒤집히고, 추적되는 설정
     * 템플릿에는 그 키가 없다(0건) — 즉 이 방어는 우리가 리뷰하는 코드 밖에 있다. 그래서 코드에서
     * 잠근다: 정말로 하나 더 열어야 하면 여기를 함께 고쳐야 하고, 그건 diff 에 보인다.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain actuatorFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(authorize -> authorize
                        // 엔드포인트 id 로 지정한다(클래스 참조 대신) — prometheus 엔드포인트 클래스는
                        // 런타임 의존인 registry 쪽에 있어 컴파일 시점에 보이지 않는다.
                        .requestMatchers(EndpointRequest.to("health", "prometheus")).permitAll()
                        .anyRequest().denyAll()
                )
                .build();
    }

    /**
     * 관리 포트가 사용자 포트와 같으면 <b>부팅을 실패시킨다</b>.
     *
     * <p>위 체인이 액추에이터를 열어두므로, 두 포트가 같아지는 순간 지표가 사용자 트래픽 포트에
     * 그대로 노출된다. 설정 실수 한 줄이 영업 지표 공개로 이어지는데 그 사이에 아무 신호가 없는
     * 조합이라, 조용히 뜨는 대신 시끄럽게 죽는 쪽을 택한다.
     */
    @Bean
    public ManagementPortGuard managementPortMustDiffer(
            @Value("${server.port:8080}") int serverPort,
            @Value("${management.server.port:}") String managementPort,
            Environment environment
    ) {
        if (managementPort == null || managementPort.isBlank()) {
            throw new IllegalStateException(
                    "management.server.port 가 없다 — 액추에이터가 사용자 포트에 열린다. "
                            + "application.yaml 의 management.server.port 를 채우거나 MANAGEMENT_PORT 를 주입할 것"
                            + "(예시: apps/live-app/src/main/resources/application.yaml.example).");
        }
        int port;
        try {
            port = Integer.parseInt(managementPort.trim());
        } catch (NumberFormatException e) {
            // 친절한 실패가 이 가드의 목적이다 — 여기서 NumberFormatException 스택트레이스로 죽으면
            // 원인이 "포트 설정 오타"라는 게 로그에서 바로 읽히지 않는다.
            throw new IllegalStateException(
                    "management.server.port 가 숫자가 아니다 — 값=" + managementPort.trim(), e);
        }
        if (port < 0) {
            // -1 은 "액추에이터를 통째로 끈다" 는 뜻이다. 부팅은 되고 지표만 조용히 사라지는데,
            // 이 가드가 배제하려던 실패 모드가 정확히 그것이다(관측이 없는 걸 아무도 모르는 상태).
            throw new IllegalStateException(
                    "management.server.port(" + port + ") 가 음수다 — 액추에이터가 비활성화되어 지표가 "
                            + "사라진다. 관측을 끄려면 이 가드를 지우는 변경이 리뷰에 보여야 한다.");
        }
        // 0 은 포트 번호가 아니라 "임의의 빈 포트를 골라라" 라는 지시다. 둘 다 0 이면 실행 시점에는
        // 서로 다른 포트가 잡히므로 같은 포트가 아니다 — 숫자만 비교하면 통합 테스트(RANDOM_PORT)가
        // 부팅조차 못 한다. 운영 설정에는 0 이 오지 않으므로 이 완화가 실제 방어를 약화시키지 않는다.
        if (port != 0 && serverPort != 0 && port == serverPort) {
            throw new IllegalStateException(
                    "management.server.port(" + port + ") 가 server.port 와 같다 — 지표가 사용자 트래픽 포트에 "
                            + "노출된다. MANAGEMENT_PORT 를 다른 값으로 줄 것.");
        }
        requireObservabilityExposed(environment);
        return new ManagementPortGuard();
    }

    /**
     * 관측에 필요한 엔드포인트. {@code health} 는 k8s 프로브용, {@code prometheus} 는 스크레이프용이다.
     *
     * <p>둘 중 하나만 있어도 되는 것이 아니다 — 하나는 파드가 Ready 가 되는 조건이고 하나는 지표가
     * 존재하는 조건이라, 빠진 쪽이 조용히 죽는다.
     */
    private static final Set<String> REQUIRED_ENDPOINTS = Set.of("health", "prometheus");

    /**
     * {@code health}·{@code prometheus} 가 <b>실제로</b> 노출돼야 부팅한다.
     *
     * <p>액추에이터 기본 노출은 {@code health} 하나다. 그래서 {@code prometheus} 가 빠지면 그
     * 엔드포인트가 아예 없고, 그 경로가 위 액추에이터 체인에 매칭되지 않아 기본 체인의
     * {@code authenticated()} 에 걸려 <b>401</b> 이 된다 — 스크레이퍼는 로그인 수단이 없으므로
     * <b>지표가 조용히 빈다</b>. 빈 대시보드는 "방송이 없어서" 와 구분되지 않는다.
     *
     * <p>⚠️ <b>실측으로 드러났다.</b> 이 앱의 컨텍스트 테스트가 CI 에서 제외돼 있던 동안 그 줄은 운영
     * 템플릿의 {@code optional} 항목이었고, 테스트를 CI 로 옮긴 첫 실행이 정확히 401 로 깨졌다.
     * 설정 한 줄이 관측 전면 중단으로 이어지는데 그 사이에 아무 신호가 없어서, 조용히 뜨는 대신
     * 시끄럽게 죽는 쪽으로 바꿨다({@code streaming-app} 이 같은 이유로 같은 가드를 갖는다).
     *
     * <p><b>include 만 보는 것은 반쪽이었다.</b> 실측: {@code include: health,prometheus} 에
     * {@code exclude: prometheus} 를 더하면 부팅은 성공하고 노출은 {@code health} 하나다 —
     * {@code exclude} 가 {@code include} 를 이긴다. 또 {@code health} 를 요구하지 않던 동안
     * {@code include: prometheus} 만 적은 설정이 통과했는데, 그러면 프로브가 404 를 받아 파드가
     * Ready 가 되지 못한다. 두 구멍 다 "부팅은 되고 조용히 고장나는" 모양이라 가드를 세운 이유와 같다.
     *
     * <p>목록을 <b>좁히는</b> 검사는 여기 없다 — 이 앱은 액추에이터 체인이 {@code health}·
     * {@code prometheus} 밖을 {@code denyAll} 로 막으므로 넓혀도 열리지 않는다. 시큐리티가 없는
     * {@code streaming-app} 은 그 반대라 허용 집합까지 코드가 잠근다.
     */
    private static void requireObservabilityExposed(Environment environment) {
        Set<String> included = readNormalized(environment, "management.endpoints.web.exposure.include");
        Set<String> excluded = readNormalized(environment, "management.endpoints.web.exposure.exclude");

        // "*" 는 양쪽에서 모두 와일드카드다 — include 의 "*" 는 전부 열고(이 앱은 체인이 막으므로
        // 허용한다), exclude 의 "*" 는 전부 되돌린다.
        Set<String> missing = new TreeSet<>();
        Set<String> revoked = new TreeSet<>();
        for (String required : REQUIRED_ENDPOINTS) {
            if (!included.contains(required) && !included.contains("*")) {
                missing.add(required);
            } else if (excluded.contains(required) || excluded.contains("*")) {
                revoked.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "management.endpoints.web.exposure.include 에 " + missing + " 가 없다 — prometheus 가 "
                            + "빠지면 스크레이퍼가 401 을 받아 지표가 조용히 비고, health 가 빠지면 k8s "
                            + "프로브가 404 를 받아 파드가 Ready 가 되지 못한다. 현재 값=" + included
                            + " / 예시: health,prometheus");
        }
        if (!revoked.isEmpty()) {
            throw new IllegalStateException(
                    "management.endpoints.web.exposure.exclude 가 " + revoked + " 를 되돌린다 — include 에 "
                            + "적혀 있어도 exclude 가 이기므로 그 엔드포인트는 생기지 않는다. 부팅은 "
                            + "성공하고 지표·프로브만 사라지는, 이 가드가 배제하려는 바로 그 침묵이다. "
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
    public static final class ManagementPortGuard {
    }

    @Bean
    public SecurityFilterChain liveFilterChain(
            HttpSecurity http,
            StatelessJwtAuthenticationFilter statelessJwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler
    ) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 관측 엔드포인트(/actuator)는 여기에 없다 — management.server.port 로 분리해
                        // 이 필터체인이 지키는 포트에 아예 뜨지 않기 때문이다. 같은 포트에 열고 permitAll
                        // 하는 방식은 "인그레스가 /actuator 를 막아줄 것"을 전제로만 성립하는데, 이
                        // 저장소에 그 제한이 없다(infra/·.gitlab-ci.yml 에 언급 없음). 없는 방어를 근거로
                        // 여는 대신 도달 자체를 끊는다. 되돌리려면 인그레스 제한을 먼저 만들 것.
                        // LiveKit webhook — Spring Security가 아니라 본문 서명(LiveKit JWT)으로 인증하므로 열어둔다.
                        .requestMatchers(HttpMethod.POST, "/webhooks/livekit").permitAll()
                        // 공개 조회를 명시한다. 이후 추가되는 GET은 자동 공개하지 않는다.
                        //
                        // ⚠️ dev 가 와일드카드(`GET /api/v1/lives/**` permitAll)를 이 목록으로 조였고,
                        // 그 조임이 chat 의 <b>이력 조회를 비공개로 만들었다</b> — 다시보기 채팅은 비회원도
                        // 본다는 것이 설계 확정이라, 목록에 명시해 되살린다. 와일드카드로 되돌리지 말 것:
                        // 조인 쪽이 맞고, chat 경로를 여기 적는 비용이 그 안전의 값이다.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/lives/rooms",
                                "/api/v1/lives/rooms/{roomId}",
                                "/api/v1/lives/rooms/{roomId}/replay",
                                "/api/v1/lives/{roomId}/chat/history").permitAll()
                        // 채팅 강퇴는 판매자와 관리자 둘 다 — 관리자는 남의 방도 통제한다.
                        // 아래 판매자 전용 규칙보다 먼저 와야 한다(먼저 맞는 규칙이 이긴다).
                        // 실제 판정은 여기서 끝나지 않는다: 자기 방인지, 방이 진행 중인지, 대상이
                        // 관리자는 아닌지는 KickUserService 가 본다. 이 줄은 그 앞의 거친 관문이다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/lives/*/chat/kick")
                                .hasAnyRole("SELLER", "ADMIN")
                        // 그 외 라이브 API는 판매자 전용
                        .requestMatchers("/api/v1/lives/**").hasRole("SELLER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(statelessJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
