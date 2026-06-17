package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.port.RateLimitResult;

import reactor.core.publisher.Mono;

/**
 * 3초 fixed window 검증. TC 번호는 RateLimiter 표(§12.1).
 * TTL 경과류(TC#2·#7)는 Redis EXPIRE 직접 조작으로 실시간 대기 없이 검증한다(flaky 방지).
 */
@Testcontainers
class RedisRateLimiterTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static RedisRateLimiter rateLimiter;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        rateLimiter = new RedisRateLimiter(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("TC#1 — 첫 요청은 허용")
    void first_request_is_allowed() {
        RateLimitResult result = rateLimiter.tryAcquire(UUID.randomUUID()).block();

        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("TC#4 — 3초 내 두 번째 요청은 거부 + retryAfterSeconds > 0")
    void second_request_within_window_is_denied() {
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block();

        RateLimitResult result = rateLimiter.tryAcquire(userId).block();

        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("TC#5 — 거부 시 retryAfterSeconds는 잔여 TTL 반영 (1~3초 범위)")
    void retryAfter_reflects_remaining_ttl() {
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block();

        RateLimitResult result = rateLimiter.tryAcquire(userId).block();

        assertThat(result.retryAfterSeconds()).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("TC#3·#8 — rate limit은 userId 기준 글로벌: 다른 유저는 독립, 같은 유저는 방이 달라도 공유")
    void limit_is_per_user_and_global_across_rooms() {
        UUID userA = UUID.randomUUID();
        rateLimiter.tryAcquire(userA).block();

        assertThat(rateLimiter.tryAcquire(userA).block().allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire(UUID.randomUUID()).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("TC#6 — 거부 요청은 윈도우를 연장하지 않는다 (fixed window)")
    void denied_request_does_not_extend_window() {
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block();
        Duration before = redisTemplate.getExpire("ratelimit:chat:" + userId).block();

        rateLimiter.tryAcquire(userId).block();
        Duration after = redisTemplate.getExpire("ratelimit:chat:" + userId).block();

        assertThat(after.toMillis()).isLessThanOrEqualTo(before.toMillis());
    }

    @Test
    @DisplayName("TC#2·#7 — 윈도우(TTL) 만료 후 요청은 다시 허용")
    void request_after_window_expiry_is_allowed() {
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block();
        redisTemplate.delete("ratelimit:chat:" + userId).block();

        assertThat(rateLimiter.tryAcquire(userId).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("TC#9 — Redis 장애 시 fail-open (allowed=true)")
    void redis_failure_fails_open() {
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class,
                Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(broken.opsForValue().setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));
        RedisRateLimiter failing = new RedisRateLimiter(broken);

        RateLimitResult result = failing.tryAcquire(UUID.randomUUID()).block();

        assertThat(result.allowed()).isTrue();
    }
}
