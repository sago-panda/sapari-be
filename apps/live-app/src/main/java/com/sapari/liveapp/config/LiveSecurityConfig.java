package com.sapari.liveapp.config;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
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
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.global.time.TimeProvider;
import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.StatelessJwtAuthenticationFilter;
import com.sapari.liveapp.security.TokenRevocationFilter;

/**
 * live-app 보안 설정 (stateless).
 *
 * <p>시청(조회)은 공개, 라이브 생성/시작/종료 등 변경은 판매자 전용이다. 홈랩이 발급한 access JWT를
 * {@link StatelessJwtAuthenticationFilter}가 서명·claims만으로 검증하며, user DB에 의존하지 않는다.
 *
 * <p><b>필터 셋의 순서가 설계다.</b> 인증({@link StatelessJwtAuthenticationFilter}) → 입장 상한
 * ({@link EnterRateLimitFilter}) → 폐기 조회({@link TokenRevocationFilter}) 이고, 셋 다
 * {@code AuthorizationFilter} 앞이라 아래 {@code authorizeHttpRequests} 판정은 이미 거둬진
 * 컨텍스트를 본다. 상한이 폐기 조회보다 앞인 이유는 비용이다 — 거부될 요청이 Redis 왕복을
 * 치지 않는다. 그 셋을 바꿔 끼우면 둘 중 하나가 깨진다.
 *
 * <p>⚠️ <b>아래 {@code permitAll} 목록이 폐기 조회의 실패 방향을 정한다.</b> 공개 경로에서는
 * 인증이 거둬져도 요청이 통과하고 principal 만 {@code null} 이 되므로, 폐기 조회가 실패를
 * "거두기" 로 처리하면 입장이 <b>조용히 게스트로 내려간다</b>
 * ({@code RevocationOnPublicRouteTest} 가 그 경로에서 200 + GUEST 를 고정한다). 그래서
 * {@link TokenRevocationFilter} 는 조회 실패를 {@code 503} 으로 끊는다 — 그 판단의 근거가
 * 이 목록이니, 공개 경로를 더하거나 뺄 때 그 필터를 함께 읽을 것.
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
     * 폐기된 토큰의 인증을 거두는 필터. 체커 둘은 {@code common/auth} 의 Redis 리더다
     * (키 규약은 {@code TokenStoreKeys} 하나에서 온다).
     *
     * <p><b>체커와 지표를 다르게 받는다.</b> 체커 둘은 필수 인자다 — {@code ObjectProvider} 로
     * 받아 없을 때 건너뛰게 두면 의존이 빠진 배포가 조용히 폐기 조회를 끄고, 그 상태가 로그 한 줄
     * 없이 정상으로 보인다. 지표는 반대로 {@code ObjectProvider} 다: 형제
     * {@code EnterRateLimitConfig} 가 적은 그대로 <b>관측 부재가 집행을 못 뜨게 하면 관측을 위해
     * 집행을 잃는다</b>({@code LiveMetricsConfig} 가 슬라이스에서 빠질 수 있다).
     */
    @Bean
    public TokenRevocationFilter tokenRevocationFilter(
            AccessTokenRevocationChecker accessTokenRevocationChecker,
            SessionRevocationChecker sessionRevocationChecker,
            ObjectProvider<LiveMetrics> metricsProvider
    ) {
        return new TokenRevocationFilter(accessTokenRevocationChecker, sessionRevocationChecker,
                metricsProvider.getIfAvailable(() -> LiveMetrics.NOOP));
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
     * {@code /actuator/env} 같은 것이 무인증으로 열린다({@code include: "*"} 한 줄로 13종이 뜬다 —
     * 실측). 무게가 다른 둘을 구분해 둔다:
     *
     * <p>{@code /heapdump} 는 <b>메모리 이미지</b>라 마스킹이라는 개념이 없다 — JWT 서명키와 LiveKit
     * API 시크릿이 그 안에 평문으로 있다. 다만 {@code include: "*"} 만으로는 <b>생기지 않는다</b>:
     * {@code @WebEndpoint} 의 {@code defaultAccess} 가 {@code NONE} 이라 access 축을 함께 뒤집어야
     * 하고, 실측에서 그 경로는 13종에 없고 401 이었다. 즉 이 체인이 막는 최악은 heapdump 가 아니라
     * 그걸 열려면 설정을 두 군데 뒤집어야 한다는 쪽이다.
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
            @Value("${management.server.port:}") String managementPort
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
     * <p>액추에이터 기본 노출은 {@code health} 하나다. 그래서 {@code prometheus} 가 없으면 그
     * 엔드포인트가 생기지 않고, 그 경로가 위 액추에이터 체인에 매칭되지 않아 기본 체인의
     * {@code authenticated()} 에 걸려 <b>401</b> 이 된다 — 스크레이퍼는 로그인 수단이 없으므로
     * <b>지표가 조용히 빈다</b>. 빈 대시보드는 "방송이 없어서" 와 구분되지 않는다. {@code health} 가
     * 없으면 같은 이유로 k8s 프로브가 401 을 받아 파드가 Ready 가 되지 못한다.
     *
     * <p>⚠️ <b>실측으로 드러났다.</b> 이 앱의 컨텍스트 테스트가 CI 에서 제외돼 있던 동안 그 줄은 운영
     * 템플릿의 {@code optional} 항목이었고, 테스트를 CI 로 옮긴 첫 실행이 정확히 401 로 깨졌다.
     * 설정 한 줄이 관측 전면 중단으로 이어지는데 그 사이에 아무 신호가 없어서, 조용히 뜨는 대신
     * 시끄럽게 죽는 쪽으로 바꿨다({@code streaming-app} 이 같은 이유로 같은 가드를 갖는다).
     *
     * <p>⚠️ <b>설정 키를 읽지 않는다.</b> 전에는 {@code exposure.include}·{@code exposure.exclude} 두
     * 키를 파싱했는데, 엔드포인트를 조용히 없애는 축이 그 둘뿐이 아니다. <b>실측</b>(같은 모양의
     * streaming-app 을 실제로 띄워 {@code WebEndpointsSupplier} 를 조회):
     * <pre>
     * management.endpoint.prometheus.enabled=false    부팅 성공   노출 [health]
     * management.endpoint.health.enabled=false        부팅 성공   노출 [prometheus]
     * management.endpoints.enabled-by-default=false   부팅 성공   노출 []
     * management.endpoint.prometheus.access=none      부팅 성공   노출 [health]
     * management.endpoints.access.default=none        부팅 성공   노출 []
     * </pre>
     * 다섯 줄 모두 두 키만 보는 가드를 <b>통과했다</b>. 셋째·다섯째는 엔드포인트가 하나도 없는데
     * 부팅에 성공한다 — 이 가드가 존재하는 이유 그 자체다. 축을 더 열거하는 길은 끝이 없어서
     * 질문을 "설정이 무엇이라고 적혀 있나" 에서 "<b>무엇이 실제로 열려 있나</b>" 로 바꿨다.
     *
     * <p>목록을 <b>좁히는</b> 검사는 여기 없다(필수 집합을 <b>포함</b>하면 통과한다) — 이 앱은
     * 액추에이터 체인이 {@code health}·{@code prometheus} 밖을 {@code denyAll} 로 막으므로 넓혀도
     * 열리지 않는다. 그 체인이 실제로 막는지는 {@code ActuatorNotOnUserPortTest} 가 {@code env} 를
     * 일부러 노출해 403 으로 밟는다. 시큐리티가 없는 {@code streaming-app} 은 그 반대라 집합이
     * <b>정확히</b> 같아야 한다.
     *
     * <p>{@code ObjectProvider} 로 받는 이유: 액추에이터가 없는 컨텍스트에서는 건너뛴다. 이 앱에
     * 액추에이터는 {@code implementation} 의존이라 운영에서는 항상 있고 빼는 변경은
     * {@code build.gradle} diff 에 보인다. 이 설정을 {@code @Import} 로 올리는 MVC 조각 테스트가
     * 없는 빈을 요구해 엉뚱하게 깨지는 쪽을 막으려는 것이다.
     *
     * <p>⚠️ <b>그 건너뛰기가 운영 구성에서 일어나지 않는지 따로 쟀다.</b> 관리 포트를 사용자 포트와
     * 다르게 주면 Boot 는 관리 전용 <b>자식 컨텍스트</b>를 만든다 — {@code WebEndpointsSupplier} 가
     * 그 자식에만 있다면 부모에서 도는 이 가드는 {@code null} 을 받아 <b>정확히 운영 구성에서만</b>
     * 조용히 통과했을 것이다. 부팅을 막는 가드가 no-op 이 되는 모양이고, 그건 이 가드가 막으려던
     * 침묵이 가드째로 조용해지는 것이다.
     *
     * <p><b>실측</b>(실제 서버 둘, 관리 포트 53154 / 사용자 포트 53151 — 즉 자식 컨텍스트가 생긴
     * 구성): 부모 컨텍스트의 {@code WebEndpointsSupplier} 빈 <b>1건</b>, 정상 설정은 부팅 성공,
     * {@code endpoints.enabled-by-default=false} 는 이 가드의 메시지로 컨텍스트 로드 실패. 즉
     * 그 빈은 부모 쪽이고 건너뛰기는 액추에이터가 아예 없는 컨텍스트에서만 일어난다.
     */
    @Bean
    public ObservabilityExposureGuard requiredEndpointsMustBeExposed(
            ObjectProvider<WebEndpointsSupplier> webEndpoints) {

        WebEndpointsSupplier supplier = webEndpoints.getIfAvailable();
        if (supplier != null) {
            verifyExposed(exposedIds(supplier));
        }
        return new ObservabilityExposureGuard();
    }

    /** 실제로 노출된 web 엔드포인트 id 들. 정렬해 둬서 실패 메시지가 안정적으로 읽힌다. */
    static Set<String> exposedIds(WebEndpointsSupplier supplier) {
        Set<String> ids = new TreeSet<>();
        supplier.getEndpoints().forEach(endpoint -> ids.add(endpoint.getEndpointId().toString()));
        return ids;
    }

    /**
     * 필수 집합이 실제 노출에 <b>들어 있는지</b>만 본다.
     *
     * <p>스프링 없이 호출할 수 있게 집합만 받는다. 실패 메시지에 <b>어떤 키를 볼지</b>를 적는 이유는
     * 이 가드가 더는 키를 읽지 않기 때문이다 — 원인이 되는 축이 여섯이고 운영자가
     * {@code access.default} 같은 건 알지 못한다.
     */
    static void verifyExposed(Set<String> actual) {
        Set<String> missing = new TreeSet<>(REQUIRED_ENDPOINTS);
        missing.removeAll(actual);
        if (missing.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "액추에이터 엔드포인트 " + missing + " 가 실제로 노출되지 않았다 — 현재 " + actual
                        + ". prometheus 가 없으면 스크레이퍼가 401 을 받아 지표가 조용히 비고, health 가 "
                        + "없으면 k8s 프로브가 401 을 받아 파드가 Ready 가 되지 못한다. 볼 키: "
                        + "management.endpoints.web.exposure.include/exclude, "
                        + "management.endpoint.<id>.enabled, management.endpoints.enabled-by-default, "
                        + "management.endpoint.<id>.access, management.endpoints.access.default.");
    }

    /** 노출 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ObservabilityExposureGuard {
    }

    /** 위 검증을 부팅에 태우기 위한 표식 빈. 동작은 없다. */
    public static final class ManagementPortGuard {
    }

    @Bean
    public SecurityFilterChain liveFilterChain(
            HttpSecurity http,
            StatelessJwtAuthenticationFilter statelessJwtAuthenticationFilter,
            EnterRateLimitFilter enterRateLimitFilter,
            TokenRevocationFilter tokenRevocationFilter,
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
                        // 그 조임이 chat 의 이력 조회를 비공개로 만들었다 — 다시보기 채팅은 비회원도
                        // 본다는 것이 설계 확정이라, 목록에 명시해 되살린다. 와일드카드로 되돌리지 말 것:
                        // 조인 쪽이 맞고, chat 경로를 여기 적는 비용이 그 안전의 값이다.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/lives/rooms",
                                "/api/v1/lives/rooms/{roomId}",
                                "/api/v1/lives/rooms/{roomId}/replay",
                                "/api/v1/lives/rooms/{roomId}/detail",
                                "/api/v1/lives/{roomId}/chat/history").permitAll()
                        // 채팅 강퇴는 판매자와 관리자 둘 다 — 관리자는 남의 방도 통제한다.
                        // 아래 판매자 전용 규칙보다 먼저 와야 한다(먼저 맞는 규칙이 이긴다).
                        // 실제 판정은 여기서 끝나지 않는다: 자기 방인지, 방이 진행 중인지, 대상이
                        // 관리자는 아닌지는 KickUserService 가 본다. 이 줄은 그 앞의 거친 관문이다.
                        .requestMatchers(HttpMethod.POST, "/api/v1/lives/*/chat/kick")
                                .hasAnyRole("SELLER", "ADMIN")
                        // 밴 해제는 ADMIN 전용이고 방 경로 밖이다 — 밴은 방이 아니라 사람에
                        // 붙으므로 어느 방에서 눌렀는지가 판정을 바꾸지 않는다. 아래
                        // `anyRequest().authenticated()` 에 맡기지 않는 이유: 그 줄은 역할을 보지
                        // 않아 구매자도 통과하고, 거부가 서비스의 권한 검사 하나에만 걸리게 된다.
                        // 강퇴를 명시한 것과 같은 이유로 거친 관문을 여기 둔다 — 실제 판정은
                        // ReleaseBanService 의 canReleaseBan 이 한 번 더 본다.
                        //
                        // ⚠️ 이 한 줄이 여는 권한의 무게: ADMIN 한 명이 그 사용자의 <2년치 누적을
                        // 조용히 접을 수 있다>. 해제는 밴만 푸는 것이 아니라 사면 기준선을 남겨
                        // 730일 창의 과거 강퇴자를 전부 세지 않게 만든다(그게 없으면 해제가 새 방
                        // 강퇴 1회까지의 유예가 되므로 그쪽이 맞는 설계다). 추적은 chat_ban_release
                        // 행이 들지만 <막는 것은 이 역할 하나>다. SELLER 를 여기 더하면 자동 승격이
                        // 요구한 '서로 다른 판매자 3·6·9명' 의 독립성을 그중 한 명이 되돌리게 된다.
                        // 메서드를 한정하지 않는다. 기준은 "폴스루에 역할 바닥이 있는가" 다 —
                        // 한정하면 그 경로의 <다른> 메서드가 이 규칙에 안 걸려 더 아래로 떨어지고,
                        // 그 아래가 /api/v1/chat/** 에는 anyRequest().authenticated()(역할 안 봄),
                        // /api/v1/lives/** 에는 SELLER 바닥이다. 둘 다 틀린 답을 준다:
                        //   chat  → 구매자가 통과 (실측: OPTIONS 가 USER 에게 200 이었다)
                        //   lives → ADMIN 전용 엔드포인트의 OPTIONS 가 <판매자에게 200, 관리자에게
                        //           403> 이 된다. Allow 헤더가 그 경로의 수락 메서드까지 흘린다.
                        // 지금 뚫리는 것은 없지만 뒤집힌 모양이고, 그 경로에 나중에 GET 이 붙으면
                        // (예: 명단 조회) 판매자에게 열리고 관리자에게 닫힌다. 그래서 아래 셋 다
                        // 메서드를 한정하지 않는다 — /chat/bans/** · /chat/kicked · /chat/ended.
                        .requestMatchers("/api/v1/chat/bans/**").hasRole("ADMIN")
                        // 사전 관리도 ADMIN 전용이고 방 경로 밖이다 — 사전은 글로벌 1개라 어느
                        // 방에서 눌렀는지가 판정을 바꾸지 않는다. 메서드를 한정하지 않는 이유는
                        // 위와 같다(폴스루가 /api/v1/chat/** 에는 역할 바닥이 없다).
                        // ⚠️ GET 도 여기 걸린다. 그게 의도다 — 이 목록은 "플랫폼이 무엇을 막는가"
                        // 의 전체이고, 우회하려는 사람에게 어떤 표기가 사전에 <없는지>를 알려 준다.
                        // 쓰기만 ADMIN 으로 두고 읽기를 아래로 떨어뜨리면 구매자가 그것을 받는다.
                        // ⚠️ /** 하나로 <그 경로 자신>까지 덮인다 — 한 줄 더 적지 않는다.
                        // 실측(2026-11-05): 패턴 /api/v1/chat/filter-words/** 가 경로
                        // /api/v1/chat/filter-words 에 맞는다(PathPattern true · AntPathMatcher
                        // true, 끝 슬래시 유무 무관). 처음에 "** 는 하위 세그먼트에만 맞는다" 로
                        // 적고 같은 경로를 두 줄로 두려 했는데 그 전제가 거짓이었다.
                        .requestMatchers("/api/v1/chat/filter-words/**").hasRole("ADMIN")
                        // 강퇴 명단 재적재도 ADMIN 전용이다. 경로가 방 아래라 아래 판매자 전용
                        // 규칙에 걸리므로 그보다 먼저 와야 한다 — 먼저 맞는 규칙이 이긴다.
                        // 순서가 뒤집히면 관리자가 403 을 받고, 집행이 꺼진 방을 고칠 사람이 없어진다.
                        // 판매자에게 열지 않는 이유는 위험이 아니라 가청성이다 — 입력이 정본이라
                        // 과다 차단을 새로 만들 수 없고(ReconcileKickListService), 이 조치가
                        // 필요하다는 신호는 KickStoreCorruptedException 로그이고 그건 운영자가 읽는다.
                        .requestMatchers("/api/v1/lives/*/chat/kicked").hasRole("ADMIN")
                        // 종료 마커 해제는 강퇴와 같은 관문이다 — 오발행으로 막히는 것이 판매자의
                        // 진행 중인 방송이고, 그걸 초 단위로 알아채는 사람도 판매자다. 재적재를
                        // ADMIN 전용으로 둔 것과 반대 방향인데, 그쪽 신호는 운영자만 읽는 로그이고
                        // 이쪽은 당사자가 즉시 아는 증상이다 — 가청성이 권한을 정한다는 같은 기준의
                        // 다른 답이다. 끝난 방을 되살리는 데 쓰이지 못하게 막는 것은 역할이 아니라
                        // 유스케이스의 LIVE 검증이다(ClearRoomEndedMarkerService).
                        .requestMatchers("/api/v1/lives/*/chat/ended").hasAnyRole("SELLER", "ADMIN")
                        // 그 외 라이브 API는 판매자 전용
                        .requestMatchers("/api/v1/lives/**").hasRole("SELLER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(statelessJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 입장 상한은 JWT 필터 <뒤>에 선다 — 인증 여부로 버킷을 가르므로
                // SecurityContextHolder 가 채워진 뒤여야 한다. 체인 밖 최상위 필터로 두면
                // springSecurityFilterChain 자체가 하나의 필터라 그 순서가 보장되지 않는다.
                .addFilterAfter(enterRateLimitFilter, StatelessJwtAuthenticationFilter.class)
                // 폐기 조회는 상한 <뒤>다 — 상한에 걸려 거부될 입장 요청이 Redis 왕복을 치지
                // 않게 한다(상한은 CPU 천장이라 그 비용을 막아 주지 못한다). 그런데도
                // AuthorizationFilter 보다는 앞이라 아래 역할 규칙은 거둬진 컨텍스트를 본다.
                // 입장 밖 경로는 상한 필터가 EnterPaths.isEnterRequest 로 즉시 통과시키므로
                // 이 순서가 그쪽 비용을 늘리지 않는다.
                .addFilterAfter(tokenRevocationFilter, EnterRateLimitFilter.class)
                .build();
    }
}
