package com.sapari.live.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.infrastructure.media.LiveKitMediaManager;
import com.sapari.live.infrastructure.metrics.MeteredLiveMediaManager;
import com.sapari.live.infrastructure.metrics.MicrometerLiveMetrics;

/**
 * 관측 빈 구성. <b>MeterRegistry 가 없어도 떠야 한다</b> — 단위 테스트가 registry 없이 컨텍스트를
 * 띄우기 때문이다. (오늘 {@code live-core} 를 쓰는 앱은 {@code live-app} 하나뿐이라 "다른 앱을 위해"
 * 는 아니다. 확인: {@code grep -rn "live-core" --include=*.gradle})
 *
 * <p>{@code @ConditionalOnBean(MeterRegistry.class)} 을 쓰지 않은 건 의도다 — 그 조건은 사용자
 * {@code @Configuration} 이 자동 구성보다 먼저 평가되는 탓에 registry 가 실제로는 있는데도 false 가
 * 되는, 순서에 의존하는 함정이다. 조용히 관측이 통째로 꺼지는 실패라 알아채기 어렵다.
 * {@link ObjectProvider} 로 런타임에 판단하면 순서와 무관하게 항상 옳다.
 */
@Configuration
public class LiveMetricsConfig {

    @Bean
    public LiveMetrics liveMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? LiveMetrics.NOOP : new MicrometerLiveMetrics(registry);
    }

    /**
     * 계측 데코레이터를 {@code @Primary} 로 앞에 세운다. 서비스들은 자기가 감싸진 걸 모른다.
     * registry 가 없으면 원본을 그대로 돌려줘 호출 경로에 아무것도 끼지 않는다.
     */
    @Bean
    @Primary
    public LiveMediaManager meteredLiveMediaManager(
            LiveKitMediaManager delegate, ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        return registry == null ? delegate : new MeteredLiveMediaManager(delegate, registry);
    }
}
