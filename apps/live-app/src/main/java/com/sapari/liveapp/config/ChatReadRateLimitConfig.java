package com.sapari.liveapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.liveapp.security.ChatHistoryRateLimitProperties;
import com.sapari.liveapp.security.ChatNoticeRateLimitProperties;
import com.sapari.liveapp.security.ChatReadRateLimitFilter;
import com.sapari.liveapp.security.ChatReadRateLimiter;

/**
 * 공개 채팅 조회(이력 · 현재 공지) 상한 배선.
 *
 * <p>필터는 빈으로 내고 {@code LiveSecurityConfig} 가 JWT 필터 뒤에 끼운다. 게스트 / 회원을 가르려면
 * 인증 뒤여야 하기 때문이다(SPR-203).
 *
 * <p>⚠️ Boot 는 {@code Filter} 빈을 서블릿 필터로도 자동 등록하므로 이 필터는 요청마다 두 번 불린다.
 * 시큐리티 체인(order -100) 안의 호출이 먼저 돌아 세고, 뒤이은 서블릿 필터 호출은 {@code OncePerRequestFilter}
 * 가 건너뛴다 — 두 번 세지 않는 근거는 그것이다. 입장 한도 필터도 같은 구조다.
 *
 * <p>⚠️ {@code @ConditionalOnProperty} 를 두지 않았다 — 끄는 길을 만들면 "상한 없이 도는 파드" 가
 * 설정 하나로 가능해진다. 빠져나갈 길은 한도를 올리는 것이고, 잘못된 값은 여기서 부팅을 막는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ChatHistoryRateLimitProperties.class, ChatNoticeRateLimitProperties.class})
public class ChatReadRateLimitConfig {

    @Bean
    public ChatReadRateLimitFilter chatReadRateLimitFilter(
            ChatHistoryRateLimitProperties history, ChatNoticeRateLimitProperties notice) {
        return new ChatReadRateLimitFilter(
                new ChatReadRateLimiter("공개 채팅 이력 조회", history.limits()),
                new ChatReadRateLimiter("공개 현재 공지 조회", notice.limits()));
    }
}
