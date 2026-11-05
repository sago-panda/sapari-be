package com.sapari.liveapp.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.live.application.port.LiveMetrics;

import com.sapari.liveapp.security.EnterRateLimitFilter;
import com.sapari.liveapp.security.EnterRateLimitProperties;
import com.sapari.liveapp.security.EnterRateLimiter;

/**
 * 입장 상한 배선.
 *
 * <p><b>{@code @ConditionalOnProperty} 가 없는 이유.</b> 형제 {@code WebhookRateLimitConfig} 는
 * 스위치로 필터 빈을 내린다 — 필터는 빈이 있으면 체인에 자동 등록되므로 "끈다" 를 표현하려면
 * 빈을 없애는 것이 맞다. 이쪽은 컨트롤러가 주입받는 협력자라 빈이 없으면 기동이 실패하고,
 * 빠져나갈 길은 한도를 올리는 것이다({@link EnterRateLimitProperties} javadoc).
 *
 * <p>⚠️ 그래서 <b>한도 변경은 롤링 재시작</b>이다 — 버킷이 생성자에서 1회 조립되고 이 저장소에
 * {@code @RefreshScope} 는 0건이다. 입장이 전면 거부되는 사고에서 그것이 복구 경로이고,
 * 재시작 중에도 거부는 계속되며 새 인스턴스는 버킷이 가득 찬 상태로 시작한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EnterRateLimitProperties.class)
public class EnterRateLimitConfig {

    /**
     * {@code LiveMetrics} 는 {@code ObjectProvider} 로 받는다 — {@code LiveMetricsConfig} 가
     * {@code MeterRegistry} 없을 때 {@code NOOP} 을 고르지만, 그 설정이 슬라이스에서 빠질 수
     * 있다. 없으면 {@code NOOP} 으로 떨어뜨린다: 지표 부재가 상한 자체를 못 뜨게 하면
     * 관측을 위해 집행을 잃는다.
     */
    @Bean
    public EnterRateLimiter enterRateLimiter(EnterRateLimitProperties properties,
            ObjectProvider<LiveMetrics> metricsProvider) {
        return new EnterRateLimiter(properties, metricsProvider.getIfAvailable(() -> LiveMetrics.NOOP));
    }

    /**
     * 필터는 {@code @Component} 가 아니다 — 그러면 Boot 가 서블릿 체인에 <b>직접</b> 등록해
     * 시큐리티 체인 밖에서 한 번 더 돌고, 그 바깥 호출은 {@code SecurityContextHolder} 가
     * 비어 있어 인증 요청을 게스트로 센다. 등록은 {@code LiveSecurityConfig} 가
     * {@code addFilterAfter} 로만 한다.
     */
    @Bean
    public EnterRateLimitFilter enterRateLimitFilter(EnterRateLimiter rateLimiter) {
        return new EnterRateLimitFilter(rateLimiter);
    }
}
