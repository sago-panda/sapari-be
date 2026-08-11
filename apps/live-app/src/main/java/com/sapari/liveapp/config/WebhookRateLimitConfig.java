package com.sapari.liveapp.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.liveapp.security.WebhookRateLimitFilter;
import com.sapari.liveapp.security.WebhookRateLimitProperties;

/**
 * webhook 레이트리밋 배선. {@code live.webhook.rate-limit.enabled=false} 로 필터 자체를 내린다 —
 * 앞단(Ingress·WAF)이 이미 같은 일을 하고 있어 이중으로 걸 이유가 없을 때를 위한 스위치다.
 *
 * <p>필터를 {@code @Component} 로 두지 않고 여기서 만드는 이유: 그러면 스위치를 꺼도 빈은 살아 있어
 * 필터 체인에 그대로 등록된다(꺼짐 여부를 필터 내부에서 다시 검사해야 하는 구조가 된다).
 *
 * <p>스위치를 여기서 원시 프로퍼티로 읽으므로 {@link WebhookRateLimitProperties} 에는 {@code enabled}
 * 필드가 없다 — 두면 아무도 읽지 않는 값이 생긴다.
 *
 * <p>조건이 {@code @Bean} 이 아니라 <b>클래스</b>에 붙어 있는 것도 의도다. {@code @Bean} 에만 걸면
 * {@code @EnableConfigurationProperties} 는 그대로 살아 바인딩·검증이 실행되고, 그러면 <b>기능을 껐는데도
 * yml 에 남은 낡은 잘못된 값이 부팅을 막는다</b>. 스위치를 내리는 상황은 대개 이 기능이 말썽을 부릴
 * 때인데, 거기서 부팅까지 실패하면 스위치가 제 역할을 못 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookRateLimitProperties.class)
@ConditionalOnProperty(
        prefix = "live.webhook.rate-limit", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class WebhookRateLimitConfig {

    @Bean
    public WebhookRateLimitFilter webhookRateLimitFilter(WebhookRateLimitProperties properties) {
        return new WebhookRateLimitFilter(properties);
    }
}
