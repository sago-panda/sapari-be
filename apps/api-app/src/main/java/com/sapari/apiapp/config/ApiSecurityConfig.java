package com.sapari.apiapp.config;

import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.security.JwtAccessDeniedHandler;
import com.sapari.common.web.security.JwtAuthenticationEntryPoint;
import com.sapari.common.web.security.JwtAuthenticationFilter;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.global.time.TimeProvider;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2SuccessHandler;
import com.sapari.customer.infrastructure.oauth.CustomerOAuth2UserService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(securedEnabled = true)
public class ApiSecurityConfig {

    private static final String[] SELLER_PUBLIC_MATCHERS = {
            "/api/v1/sellers/auth/signup",
            "/api/v1/sellers/auth/signup/email-verifications",
            "/api/v1/sellers/auth/signup/email-verifications/confirm",
            "/api/v1/sellers/auth/signup/check-email",
            "/api/v1/sellers/auth/signup/check-phone",
            "/api/v1/sellers/auth/check-nickname",
            "/api/v1/sellers/auth/signup/check-store-name",
            "/api/v1/sellers/auth/login",
            "/api/v1/sellers/auth/token/reissue"
    };

    private static final String[] CUSTOMER_PROTECTED_MATCHERS = {
            "/api/v1/customers/auth/me",
            "/api/v1/customers/auth/me/nickname",
            "/api/v1/customers/auth/logout"
    };

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

    @Bean
    public PasswordEncoder passwordEncoder() {
        String encodingId = "argon2id";

        PasswordEncoder argon2PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

        return new DelegatingPasswordEncoder(
                encodingId,
                Map.of(encodingId, argon2PasswordEncoder)
        );
    }

    @Bean
    @Order(1)
    public SecurityFilterChain sellerFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler
    ) throws Exception {
        return http
                .securityMatcher("/api/v1/sellers/auth/**")
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
                        .requestMatchers(SELLER_PUBLIC_MATCHERS).permitAll()
                        .anyRequest().hasRole("SELLER")
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain customerFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint,
            JwtAccessDeniedHandler jwtAccessDeniedHandler,
            CustomerOAuth2UserService customerOAuth2UserService,
            CustomerOAuth2SuccessHandler customerOAuth2SuccessHandler
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
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(authorization -> authorization
                                .baseUri("/api/v1/customers/auth/oauth2/authorization")
                        )
                        .redirectionEndpoint(redirection -> redirection
                                .baseUri("/api/v1/customers/auth/oauth2/code/*")
                        )
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(customerOAuth2UserService)
                        )
                        .successHandler(customerOAuth2SuccessHandler)
                )
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(CUSTOMER_PROTECTED_MATCHERS).authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
