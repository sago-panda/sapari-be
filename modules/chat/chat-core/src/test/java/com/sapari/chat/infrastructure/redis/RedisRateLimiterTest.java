package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.port.RateLimitResult;

import reactor.core.publisher.Mono;

/**
 * 3초 fixed window 검증. TC 번호는 설계 문서 검증 계획의 RateLimiter 표를 따른다.
 * TTL 경과류(TC#2·#7)는 Redis EXPIRE 직접 조작으로 실시간 대기 없이 검증한다(flaky 방지).
 */
@Testcontainers
class RedisRateLimiterTest {


    /** StepVerifier 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
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
        // given
        RateLimitResult result = rateLimiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

        // when & then
        assertThat(result.allowed()).isTrue();
        assertThat(result.retryAfterSeconds()).isZero();
    }

    @Test
    @DisplayName("TC#4 — 3초 내 두 번째 요청은 거부 + retryAfterSeconds > 0")
    void second_request_within_window_is_denied() {
        // given
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);

        RateLimitResult result = rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);

        // when & then
        assertThat(result.allowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("TC#5 — 거부 시 retryAfterSeconds는 잔여 TTL 반영 (1~3초 범위)")
    void retryAfter_reflects_remaining_ttl() {
        // given
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);

        RateLimitResult result = rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);

        // when & then
        assertThat(result.retryAfterSeconds()).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("TC#3·#8 — rate limit은 userId 기준 글로벌: 다른 유저는 독립, 같은 유저는 방이 달라도 공유")
    void limit_is_per_user_and_global_across_rooms() {
        // given
        UUID userA = UUID.randomUUID();
        rateLimiter.tryAcquire(userA).block(VERIFY_CEILING);

        // when & then
        assertThat(rateLimiter.tryAcquire(userA).block(VERIFY_CEILING).allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING).allowed()).isTrue();
    }

    @Test
    @DisplayName("TC#6 — 거부 요청은 윈도우를 연장하지 않는다 (fixed window)")
    void denied_request_does_not_extend_window() {
        // given
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
        Duration before = redisTemplate.getExpire("ratelimit:chat:" + userId).block(VERIFY_CEILING);

        rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
        Duration after = redisTemplate.getExpire("ratelimit:chat:" + userId).block(VERIFY_CEILING);

        // when & then
        assertThat(after.toMillis()).isLessThanOrEqualTo(before.toMillis());
    }

    @Test
    @DisplayName("TC#2·#7 — 윈도우(TTL) 만료 후 요청은 다시 허용")
    void request_after_window_expiry_is_allowed() {
        // given
        UUID userId = UUID.randomUUID();
        rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
        redisTemplate.delete("ratelimit:chat:" + userId).block(VERIFY_CEILING);

        // when & then
        assertThat(rateLimiter.tryAcquire(userId).block(VERIFY_CEILING).allowed()).isTrue();
    }

    /**
     * TC#9 — Redis 장애 시 <b>첫 요청은</b> 허용한다.
     *
     * <p>한때 이 테스트의 이름이 "fail-open" 이었고 단언이 이 한 줄뿐이었다. 그 모양으로는 강등을
     * 되돌려도(무조건 {@code allowed=true}) 멀쩡히 초록이라, <b>지워진 계약을 주장하는 초록 테스트</b>가
     * 된다 — 다음 사람이 "fail-open" 으로 grep 하면 이것을 찾는다. 창 안의 두 번째 요청이 거부되는지는
     * {@code RedisRateLimiterDegradedTest} 가 본다. 여기서 재는 것은 "장애가 첫 요청을 막지 않는다"
     * 하나다(가용성 우선).
     */
    @Test
    @DisplayName("TC#9 — Redis 장애가 첫 요청을 막지 않는다 (상한은 로컬 창으로 강등 — Degraded 테스트 참고)")
    void redis_failure_still_allows_the_first_request() {
        // given
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class,
                Mockito.RETURNS_DEEP_STUBS);
        BDDMockito.given(broken.opsForValue().setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .willReturn(Mono.error(new RuntimeException("connection refused")));
        RedisRateLimiter failing = new RedisRateLimiter(broken);

        RateLimitResult result = failing.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

        // when & then
        assertThat(result.allowed()).isTrue();
    }
}
