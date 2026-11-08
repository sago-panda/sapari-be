package com.sapari.liveapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.sapari.liveapp.security.ChatHistoryRateLimitFilter;
import com.sapari.liveapp.security.ChatHistoryRateLimitProperties;
import com.sapari.liveapp.security.ChatHistoryRateLimiter;

/**
 * 공개 채팅 이력 조회 상한 배선.
 *
 * <p><b>형제 {@link EnterRateLimitConfig} 와 등록 방식이 다르다.</b> 그쪽 필터는
 * {@code @Component} 가 아니고 {@code LiveSecurityConfig} 가 체인 안에 직접 끼운다 — 인증 여부로
 * 버킷을 가르므로 {@code SecurityContextHolder} 뒤에 서야 하기 때문이다. 이쪽은 신원을 보지 않으니
 * 체인 <b>앞</b>에 두는 쪽이 낫고(거부가 JWT 파싱 비용을 안 쓴다), 그래서 서블릿 필터로 등록하고
 * 순서를 시큐리티 체인보다 앞으로 못 박는다.
 *
 * <p><b>순서는 {@code Ordered.HIGHEST_PRECEDENCE} 기준으로 적는다</b> — 이 저장소가 "시큐리티
 * 체인보다 먼저" 를 표현하는 방식이고 선례가 {@code CommonWebFilterConfig} 의 MDC 필터다.
 * 거기에 {@code + 10} 을 더해 <b>MDC 필터 뒤</b>에 선다: 거부 WARN 이 MDC 컨텍스트를 실어야
 * 어느 요청이었는지 추적되고, 그래도 시큐리티 체인(기본 순서 -100)보다는 한참 앞이다.
 *
 * <p>⚠️ {@code @ConditionalOnProperty} 를 두지 않았다 — 끄는 길을 만들면 "상한 없이 도는 파드" 가
 * 설정 하나로 가능해진다. 빠져나갈 길은 한도를 올리는 것이고, 잘못된 값은 부팅에서 막힌다
 * ({@link ChatHistoryRateLimitProperties}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatHistoryRateLimitProperties.class)
public class ChatHistoryRateLimitConfig {

    @Bean
    public ChatHistoryRateLimiter chatHistoryRateLimiter(ChatHistoryRateLimitProperties properties) {
        return new ChatHistoryRateLimiter(properties);
    }

    @Bean
    public FilterRegistrationBean<ChatHistoryRateLimitFilter> chatHistoryRateLimitFilterRegistration(
            ChatHistoryRateLimiter rateLimiter) {
        FilterRegistrationBean<ChatHistoryRateLimitFilter> registration =
                new FilterRegistrationBean<>(new ChatHistoryRateLimitFilter(rateLimiter));
        // MDC 필터(HIGHEST_PRECEDENCE) 뒤, 시큐리티 체인(-100) 앞.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
