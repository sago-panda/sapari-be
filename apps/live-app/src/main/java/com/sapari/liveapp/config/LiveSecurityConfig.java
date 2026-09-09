package com.sapari.liveapp.config;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
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
     * {@code /actuator/env}·{@code /heapdump} 가 무인증으로 열리고, 그 응답에는 JWT 서명키와
     * LiveKit API 시크릿이 담긴다. 설정 한 줄이 자격증명 공개가 되는 경로라 코드에서도 잠근다 —
     * 정말로 하나 더 열어야 하면 여기를 함께 고쳐야 하고, 그건 diff 에 보인다.
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
                        // 시청(조회)은 공개
                        .requestMatchers(HttpMethod.GET, "/api/v1/lives/**").permitAll()
                        // 생성/시작/종료 등 변경은 판매자 전용
                        .requestMatchers("/api/v1/lives/**").hasRole("SELLER")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(statelessJwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
