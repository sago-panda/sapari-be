package com.sapari.liveapp.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * LiveSecurityConfig 의 {@code .cors()} 가 이 빈(이름 corsConfigurationSource)을 쓴다. CorsFilter 가 인증 필터보다
 * 앞이라 사전 요청(OPTIONS)은 인증 없이 응답된다.
 * 자격 증명은 허용하지 않는다 — 인증이 쿠키가 아니라 Authorization 헤더라 필요 없다.
 * 노출 헤더 Retry-After 는 입장 상한 등의 429 에서 프론트가 재시도 시점을 읽기 위해서다.
 */
@Configuration
@EnableConfigurationProperties(CorsProperties.class)
public class LiveCorsConfig {

    static final List<String> METHODS = List.of(
            HttpMethod.GET.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.DELETE.name());
    static final List<String> REQUEST_HEADERS = List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE);
    static final List<String> RESPONSE_HEADERS = List.of(HttpHeaders.RETRY_AFTER);
    /** 사전 요청 결과를 브라우저가 묵혀 두는 시간. 크롬은 2시간에서 자른다. */
    static final Duration PREFLIGHT_CACHE = Duration.ofHours(1);

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source; // 등록이 없으면 CorsFilter 가 아무 헤더도 붙이지 않는다 = CORS 끔
        }
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(METHODS);
        configuration.setAllowedHeaders(REQUEST_HEADERS);
        configuration.setExposedHeaders(RESPONSE_HEADERS);
        configuration.setMaxAge(PREFLIGHT_CACHE);
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
