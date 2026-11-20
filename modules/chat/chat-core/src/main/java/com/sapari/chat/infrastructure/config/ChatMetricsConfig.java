package com.sapari.chat.infrastructure.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 관측 빈 구성. <b>MeterRegistry 가 없어도 떠야 한다</b> — chat-core 를 쓰는 앱이 둘이고
 * ({@code streaming-app}, {@code live-app}) 단위 테스트는 registry 없이 컨텍스트를 띄운다.
 *
 * <p>{@code @ConditionalOnBean(MeterRegistry.class)} 을 쓰지 않은 건 의도다 — 그 조건은 사용자
 * {@code @Configuration} 이 자동 구성보다 먼저 평가되는 탓에 registry 가 실제로는 있는데도 false 가
 * 되는, 순서에 의존하는 함정이다. 조용히 관측이 통째로 꺼지는 실패라 알아채기 어렵다.
 * {@link ObjectProvider} 로 런타임에 판단하면 순서와 무관하게 항상 옳다. (같은 결정과 같은 사유가
 * live 에 이미 있다.)
 */
@Configuration
public class ChatMetricsConfig {

    @Bean
    public ChatMetrics chatMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? ChatMetrics.NOOP : new MicrometerChatMetrics(registry);
    }
}
