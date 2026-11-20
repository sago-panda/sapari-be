package com.sapari.chat.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 관측 빈 선택 — <b>registry 가 없어도 떠야 하고, 있으면 반드시 써야 한다.</b>
 *
 * <p>둘 다 조용히 틀릴 수 있다. 없을 때 못 뜨면 registry 없는 컨텍스트(단위 테스트, 아직 actuator 를
 * 안 붙인 앱)가 통째로 깨지고, 있는데 안 쓰면 <b>빌드는 초록인데 지표가 영원히 비어 있다</b> — 그
 * 상태는 "장애가 없다" 와 구분되지 않아서, 알림을 걸어 둔 쪽이 오히려 더 위험해진다.
 *
 * <p><b>registry 를 자동 구성으로 올린다.</b> 테스트가 직접 등록한 빈으로는 이 테스트가 재려는 함정이
 * 안 재진다 — {@code @ConditionalOnBean(MeterRegistry.class)} 의 문제는 "빈이 없을 때 false" 가
 * 아니라 <b>사용자 {@code @Configuration} 이 자동 구성보다 먼저 평가돼, 실제로는 올라올 registry 를
 * 못 보고 false 가 되는 것</b>이다. 그러니 registry 가 사용자 빈으로 먼저 등록돼 있으면 조건부
 * 구현조차 통과해 버린다. 아래 뮤턴트로 확인했다: 자동 구성으로 올리면 조건부 버전이 NOOP 을 고르며
 * 실패하고, 사용자 빈으로 올리면 통과한다.
 */
@DisplayName("ChatMetricsConfig — registry 유무로 구현을 고른다")
class ChatMetricsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatMetricsConfig.class);

    /** registry 를 <b>자동 구성으로</b> 공급한다 — 평가 순서를 실제와 같게 만들기 위한 장치다. */
    @AutoConfiguration
    static class RegistryAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    @DisplayName("MeterRegistry 가 없으면 NOOP 으로 뜬다 — 관측이 없다고 앱이 못 뜨면 안 된다")
    void fallsBackToNoopWithoutARegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatMetrics.class)).isSameAs(ChatMetrics.NOOP);
        });
    }

    @Test
    @DisplayName("⭐ 자동 구성이 registry 를 올리면 micrometer 구현을 쓴다 — 조건부 빈이면 여기서 NOOP 이 된다")
    void usesTheMicrometerAdapterWhenAutoConfigurationProvidesARegistry() {
        runner.withConfiguration(AutoConfigurations.of(RegistryAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ChatMetrics.class))
                            .as("자동 구성이 올린 registry 를 못 보고 NOOP 을 골랐다 — 지표가 조용히 전부 빈다")
                            .isInstanceOf(MicrometerChatMetrics.class);
                });
    }
}
