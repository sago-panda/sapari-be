package com.sapari.streamingapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

/**
 * Redis 커맨드 상한의 <b>값과 배선</b>을 고정한다.
 *
 * <p>이 상한이 없으면 차가운 커넥션에서 커맨드가 Lettuce 기본값(60초)까지 pending 이고, 연산자
 * 상한({@code .timeout()})은 그 구간을 덮지 못한다 — 실측 60,245ms → 1,115ms. 즉 이 값이 chat 의
 * 모든 Redis 호출에 대한 실질 상한이다.
 */
@DisplayName("Redis 커맨드 상한")
class RedisTimeoutConfigTest {

    @Test
    @DisplayName("⭐ 기본값이 1초다 — 설정이 없어도 적용되는 값이라 여기가 유일한 출처다")
    void the_default_command_timeout_is_one_second() {
        // given: application*.yml 은 추적되지 않으므로 이 @Value 기본값이 운영에 적용되는 값이다
        String declared = valueDefaultOf("redisCommandTimeout");

        // when & then
        assertThat(DurationStyle.detectAndParse(declared))
                .as("커맨드 상한 기본값이 바뀌었다 — chat 의 모든 Redis 호출 상한이다")
                .isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("⭐ 커스터마이저가 그 값을 빌더에 실제로 넣는다 — 배선이 빠지면 60초로 돌아간다")
    void the_customizer_applies_the_timeout_to_the_builder() {
        // given
        Duration given = Duration.ofMillis(1234);

        // when
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        new RedisTimeoutConfig().redisCommandTimeout(given).customize(builder);

        // then
        assertThat(builder.build().getCommandTimeout())
                .as("커스터마이저가 상한을 넣지 않았다 — Lettuce 기본값 60초가 그대로 산다")
                .isEqualTo(given);
    }

    /** {@code @Value("${키:기본값}")} 에서 기본값만 뽑는다 — 소스 파일을 경로로 읽지 않기 위해. */
    private String valueDefaultOf(String methodName) {
        for (Method method : RedisTimeoutConfig.class.getDeclaredMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            for (Parameter parameter : method.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().contains(":")) {
                    String expression = value.value();
                    return expression.substring(expression.indexOf(':') + 1, expression.length() - 1);
                }
            }
        }
        throw new AssertionError(methodName + " 의 @Value 기본값을 찾지 못했다");
    }
}
