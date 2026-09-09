package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.infrastructure.config.LiveMetricsConfig;
import com.sapari.live.infrastructure.media.LiveKitMediaManager;

/**
 * 관측 빈이 <b>MeterRegistry 유무와 무관하게</b> 컨텍스트를 띄우는지 확인한다.
 *
 * <p>이걸 테스트로 박아두는 이유: registry 가 없을 때 깨지면 live-core 를 쓰는 다른 앱이 부팅에
 * 실패하고, 반대로 registry 가 있는데 NOOP 이 선택되면 <b>아무 에러 없이 관측만 통째로 사라진다</b>.
 * 후자가 더 위험하다 — 대시보드가 빈 걸 보고서야 알게 된다.
 */
class LiveMetricsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LiveMetricsConfig.class, StubMediaManager.class);

    @Test
    @DisplayName("MeterRegistry 가 없으면 NOOP 과 원본 어댑터가 선택되고 컨텍스트는 정상적으로 뜬다")
    void withoutRegistry_fallsBackToNoOp() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LiveMetrics.class)).isSameAs(LiveMetrics.NOOP);
            assertThat(context.getBean(LiveMediaManager.class)).isNotInstanceOf(MeteredLiveMediaManager.class);
        });
    }

    @Test
    @DisplayName("MeterRegistry 가 있으면 micrometer 구현과 계측 데코레이터가 선택된다")
    void withRegistry_usesMicrometer() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LiveMetrics.class)).isInstanceOf(MicrometerLiveMetrics.class);
            assertThat(context.getBean(LiveMediaManager.class)).isInstanceOf(MeteredLiveMediaManager.class);
        });
    }

    @Configuration
    static class StubMediaManager {
        @Bean
        LiveKitMediaManager liveKitMediaManager() {
            return mock(LiveKitMediaManager.class);
        }
    }
}
