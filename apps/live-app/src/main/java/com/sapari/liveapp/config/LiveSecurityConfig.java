package com.sapari.liveapp.config;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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
