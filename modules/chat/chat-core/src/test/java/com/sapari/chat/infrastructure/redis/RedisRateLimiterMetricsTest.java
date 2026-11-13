package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

/**
 * 레이트리밋이 <b>내려앉은 구간이 밖으로 나가는지</b> 본다.
 *
 * <p>강등은 허용량을 Pod 수만큼 늘리는 선택이다. 그 선택 자체는 의도이고 통째로 사라지는 것보다 낫다 —
 * 다만 <b>그 구간이 얼마나 길었는지를 모르면 그 판단이 맞았는지 확인할 방법이 없다.</b>
 */
@DisplayName("레이트리밋 관측 — 강등된 구간이 지표로 나간다")
class RedisRateLimiterMetricsTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private final MeterRegistry registry = new SimpleMeterRegistry();

    private double count(String mode) {
        Counter counter = registry.find("chat.ratelimit.degraded").tags("mode", mode).counter();
        return counter == null ? 0 : counter.count();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ReactiveStringRedisTemplate templateAnswering(Flux reply) {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        given(template.execute(any(RedisScript.class), anyList(), anyList())).willReturn(reply);
        return template;
    }

    @Test
    @DisplayName("⭐ Redis 장애로 로컬 판정에 내려앉으면 mode=local_window 가 오른다")
    void theLocalFallbackIsReported() {
        // given
        RedisRateLimiter limiter = new RedisRateLimiter(
                templateAnswering(Flux.error(new RedisConnectionFailureException("Redis 가 죽었다"))),
                new MicrometerChatMetrics(registry));

        // when: 강등돼도 판정은 나온다
        RateLimitResult result = limiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

        // then
        assertThat(result).isNotNull();
        assertThat(result.allowed()).as("강등은 집행을 없애지 않는다 — 첫 요청은 허용").isTrue();
        assertThat(count("local_window")).isEqualTo(1);
    }

    @Test
    @DisplayName("정상 경로에서는 강등 지표가 아예 생기지 않는다 — 평상시 0 이어야 알림을 걸 수 있다")
    void aHealthyPathReportsNothing() {
        // given: 거부 응답도 정상 경로다 — 판정을 Redis 가 내렸다
        RedisRateLimiter limiter = new RedisRateLimiter(
                templateAnswering(Flux.just(List.of(0L, 2_000L))),
                new MicrometerChatMetrics(registry));

        // when
        limiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

        // then
        assertThat(registry.find("chat.ratelimit.degraded").counters()).isEmpty();
    }
}
