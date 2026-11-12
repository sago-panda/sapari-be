package com.sapari.liveapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.sapari.liveapp.security.ChatNoticeRateLimitFilter;
import com.sapari.liveapp.security.ChatNoticeRateLimitProperties;
import com.sapari.liveapp.security.ChatNoticeRateLimiter;

/**
 * 공개 현재 공지 조회 상한 배선. 등록 방식(서블릿 필터, 시큐리티 체인 앞)과 끄는 길을 두지 않는
 * 이유는 형제 {@link ChatHistoryRateLimitConfig} 와 같다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatNoticeRateLimitProperties.class)
public class ChatNoticeRateLimitConfig {

    @Bean
    public ChatNoticeRateLimiter chatNoticeRateLimiter(ChatNoticeRateLimitProperties properties) {
        return new ChatNoticeRateLimiter(properties);
    }

    @Bean
    public FilterRegistrationBean<ChatNoticeRateLimitFilter> chatNoticeRateLimitFilterRegistration(
            ChatNoticeRateLimiter rateLimiter) {
        FilterRegistrationBean<ChatNoticeRateLimitFilter> registration =
                new FilterRegistrationBean<>(new ChatNoticeRateLimitFilter(rateLimiter));
        // 형제 이력 필터(+10) 바로 뒤. 경로가 겹치지 않아 둘 사이 순서는 결과를 바꾸지 않는다.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 11);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
