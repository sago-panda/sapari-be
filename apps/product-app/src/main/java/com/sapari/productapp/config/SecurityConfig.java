package com.sapari.productapp.config;

import tools.jackson.databind.ObjectMapper;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.CurrentUserId;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.common.web.security.JwtAuthenticationFilter;
import com.sapari.global.time.TimeProvider;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 상품 앱 시큐리티 설정. 두 개의 stateless JWT 필터 체인으로 구성한다.
 *
 * <p>판매자 상품 관리 경로({@code /api/v1/seller/**})는 {@code ROLE_SELLER}를 요구하고,
 * 구매자 공개 조회({@code GET /api/v1/products/*})와 문서 경로만 허용하며 그 외는 기본 거부한다.
 * 인증 주체의 userId는 {@link CurrentUserId}로 컨트롤러에 주입된다.
 *
 * <p>로그인/토큰 발급은 이 앱의 책임이 아니므로(인증은 api-app이 담당) 비밀번호 인코더·OAuth2 설정은 두지 않는다.
 * JWT 검증에 필요한 빈({@link JwtTokenProvider}·revocation checker·{@link UserDetailsService})은
 * 클래스패스의 {@code common:auth}·{@code user-core}에서 컴포넌트 스캔으로 등록된다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class SecurityConfig {

    /** springdoc OpenAPI 문서·Swagger UI 정적 경로 — 인증 없이 공개한다. */
    private static final String[] SWAGGER_MATCHERS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**"
    };

    /**
     * 구매자 공개 조회 경로 — GET만 허용한다. 단일 세그먼트({@code /api/v1/products/{id}})로 좁혀,
     * 하위에 판매자/내부용 GET이 추가되더라도 자동 공개되지 않게 한다(필요한 공개 GET은 명시 추가).
     */
    private static final String[] PUBLIC_GET_MATCHERS = {
            "/api/v1/products/*"
    };

    /**
     * 인증 실패(401) 응답을 표준 봉투로 직렬화하는 진입점 핸들러를 등록한다.
     *
     * @param objectMapper 응답 직렬화용 매퍼
     * @param timeProvider 응답 타임스탬프 소스
     * @return JWT 인증 진입점
     */
    @Bean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint(
            ObjectMapper objectMapper,
            TimeProvider timeProvider
    ) {
        return new JwtAuthenticationEntryPoint(objectMapper, timeProvider);
    }

    /**
     * 인가 실패(403) 응답을 표준 봉투로 직렬화하는 핸들러를 등록한다.
     *
     * @param objectMapper 응답 직렬화용 매퍼
     * @param timeProvider 응답 타임스탬프 소스
     * @return JWT 접근 거부 핸들러
     */
    @Bean
    public JwtAccessDeniedHandler jwtAccessDeniedHandler(
            ObjectMapper objectMapper,
            TimeProvider timeProvider
    ) {
        return new JwtAccessDeniedHandler(objectMapper, timeProvider);
    }

    /**
     * Authorization 헤더의 access token을 파싱해 SecurityContext에 인증을 채우는 필터를 등록한다.
     *
     * @param jwtTokenProvider             토큰 파싱·검증기
     * @param userDetailsService           userId로 사용자 권한을 로드하는 서비스
     * @param accessTokenRevocationChecker access token 폐기 여부 확인기
     * @param sessionRevocationChecker     세션 폐기 여부 확인기
     * @return JWT 인증 필터
     */
    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            UserDetailsService userDetailsService,
            AccessTokenRevocationChecker accessTokenRevocationChecker,
            SessionRevocationChecker sessionRevocationChecker
    ) {
        return new JwtAuthenticationFilter(
                jwtTokenProvider,
                userDetailsService,
                accessTokenRevocationChecker,
                sessionRevocationChecker
        );
    }

    /**
     * 판매자 상품 관리 체인. {@code /api/v1/seller/**} 전체를 {@code ROLE_SELLER}로 보호한다.
     *
     * @param http                        HTTP 시큐리티 빌더
     * @param jwtAuthenticationFilter     인증 필터
     * @param jwtAuthenticationEntryPoint 401 핸들러
     * @param jwtAccessDeniedHandler      403 핸들러
     * @return 판매자 경로 필터 체인
     * @throws Exception 빌드 실패 시
     */
    @Bean
    @Order(1)
    public SecurityFilterChain sellerFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler
    ) throws Exception {
        return http
                .securityMatcher("/api/v1/seller/**")
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
                        .anyRequest().hasRole("SELLER")
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * 구매자 공개·문서 체인. 상품 공개 조회({@code GET /api/v1/products/**})와 Swagger만 허용하고, 그 외는 기본 거부한다.
     *
     * <p>토큰이 있으면 필터가 인증을 채우지만(이후 확장 대비), 공개 경로라 인증을 강제하지 않는다.
     * 명시 허용 목록 밖 요청은 {@code denyAll}로 거부해, 향후 {@code /seller/**} 밖에 비-GET 엔드포인트가
     * 추가되더라도 매처 누락으로 무인증 노출되는 일을 막는다(default-open → default-deny).
     *
     * @param http                        HTTP 시큐리티 빌더
     * @param jwtAuthenticationFilter     인증 필터
     * @param jwtAuthenticationEntryPoint 401 핸들러
     * @param jwtAccessDeniedHandler      403 핸들러
     * @return 공개 경로 필터 체인
     * @throws Exception 빌드 실패 시
     */
    @Bean
    @Order(2)
    public SecurityFilterChain publicFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
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
                        .requestMatchers(SWAGGER_MATCHERS).permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET_MATCHERS).permitAll()
                        // 명시 허용 외 전부 거부(기본 deny) — /seller/** 밖에 비-GET 엔드포인트가 추가돼도 무인증 노출되지 않게 한다
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
