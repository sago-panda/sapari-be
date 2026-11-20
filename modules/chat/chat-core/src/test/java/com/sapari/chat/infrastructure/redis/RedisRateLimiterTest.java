package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.port.RateLimitResult;

import reactor.core.publisher.Flux;

/**
 * 평균 3초에 1건 · 버스트 3 (GCRA) — 실제 Redis 스크립트로 잰다.
 *
 * <p>시간 경과는 기다리지 않고 <b>키의 TAT 를 직접 옮겨</b> 만든다(flaky 방지). 판정 시각은 Redis
 * {@code TIME} 이라 테스트가 시계를 주입할 수 없고, 대신 "그만큼 시간이 지난 상태" 를 키로 만든다.
 */
@Testcontainers
class RedisRateLimiterTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static RedisRateLimiter rateLimiter;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        rateLimiter = new RedisRateLimiter(redisTemplate, ChatMetrics.NOOP);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    private static RateLimitResult acquire(UUID userId) {
        return rateLimiter.tryAcquire(userId).block(VERIFY_CEILING);
    }

    private static String key(UUID userId) {
        return "ratelimit:chat:" + userId;
    }

    private static long storedTat(UUID userId) {
        return Long.parseLong(redisTemplate.opsForValue().get(key(userId)).block(VERIFY_CEILING));
    }

    /** Redis 서버 시각(ms) — 스크립트가 쓰는 시계와 같은 것. */
    private static long redisNowMillis() {
        return redisTemplate.execute(connection -> connection.serverCommands().time()).next()
                .block(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 쉬었던 사용자는 3건을 연달아 보내고 4번째가 거부된다")
    void a_rested_user_gets_a_burst_of_three() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        RateLimitResult first = acquire(userId);
        RateLimitResult second = acquire(userId);
        RateLimitResult third = acquire(userId);
        RateLimitResult fourth = acquire(userId);

        // then: 고정 창이면 두 번째에서 막혔다
        assertThat(first.allowed()).isTrue();
        assertThat(second.allowed()).as("두 번째 — 고정 창이었다면 여기서 거부").isTrue();
        assertThat(third.allowed()).isTrue();
        assertThat(fourth.allowed()).as("버스트 3 을 넘었다").isFalse();
    }

    @Test
    @DisplayName("⭐ 버스트를 다 쓴 뒤의 거부는 한 간격(3초) 뒤를 알려 준다")
    void retry_after_is_one_interval_after_the_burst() {
        // given
        UUID userId = UUID.randomUUID();
        acquire(userId);
        acquire(userId);
        acquire(userId);

        // when
        RateLimitResult denied = acquire(userId);

        // then: 연달아 3건 → TAT = t+9s, 허용 시각 = TAT - 6s = t+3s. 테스트 왕복만큼 덜 남아 올리면 3 이다
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("⭐ 한 간격이 지나면 한 건이 더 허용되고 그다음은 다시 거부다 — 평균 3초에 1건으로 수렴한다")
    void one_interval_later_exactly_one_more_is_allowed() {
        // given: 버스트를 다 쓴다
        UUID userId = UUID.randomUUID();
        acquire(userId);
        acquire(userId);
        acquire(userId);
        assertThat(acquire(userId).allowed()).isFalse();

        // when: 3초가 지난 상태를 만든다 — TAT 를 한 간격 당긴다(TTL 도 같이 줄여 모양을 맞춘다)
        long tat = storedTat(userId);
        long shifted = tat - RedisRateLimiter.EMISSION_INTERVAL.toMillis();
        redisTemplate.opsForValue().set(key(userId), Long.toString(shifted),
                Duration.ofMillis(Math.max(1, shifted - redisNowMillis()))).block(VERIFY_CEILING);

        // then
        assertThat(acquire(userId).allowed()).as("한 간격이 지났다").isTrue();
        assertThat(acquire(userId).allowed()).as("한 간격에 한 건뿐이다").isFalse();
    }

    @Test
    @DisplayName("⭐ 오래 쉬어도 버스트는 3 이다 — 지난 TAT 를 지금으로 끌어올린다")
    void a_long_idle_does_not_bank_more_than_the_burst() {
        // given: 10분 전 TAT 가 남아 있는 키(TTL 이 아직 남은 상태)
        UUID userId = UUID.randomUUID();
        long tenMinutesAgo = redisNowMillis() - Duration.ofMinutes(10).toMillis();
        redisTemplate.opsForValue().set(key(userId), Long.toString(tenMinutesAgo), Duration.ofMinutes(1))
                .block(VERIFY_CEILING);

        // when
        int allowed = 0;
        for (int i = 0; i < 10; i++) {
            if (acquire(userId).allowed()) {
                allowed++;
            }
        }

        // then: 지난 TAT 를 그대로 쓰면 쉰 시간 ÷ 3초 만큼 연달아 허용된다
        assertThat(allowed).isEqualTo(RedisRateLimiter.BURST);
        // 그리고 그 판정을 Redis 가 내렸는지 본다 — 끌어올리지 않으면 SET 의 PX 가 음수라 스크립트가
        // 에러를 내고 로컬 판정으로 떨어지는데, 로컬도 버스트 3 이라 위 단언만으로는 구분되지 않는다
        // (실측: 끌어올림을 지운 변이가 위 단언을 통과했다).
        assertThat(storedTat(userId)).as("Redis 가 TAT 를 쓰지 않았다 — 판정이 로컬로 떨어졌다")
                .isGreaterThan(redisNowMillis());
    }

    @Test
    @DisplayName("⭐ 거부는 TAT 를 밀지 않는다 — 막힌 채 계속 두드려도 해제가 미뤄지지 않는다")
    void a_denial_does_not_move_the_tat() {
        // given
        UUID userId = UUID.randomUUID();
        acquire(userId);
        acquire(userId);
        acquire(userId);
        long before = storedTat(userId);

        // when
        for (int i = 0; i < 5; i++) {
            assertThat(acquire(userId).allowed()).isFalse();
        }

        // then
        assertThat(storedTat(userId)).isEqualTo(before);
    }

    @Test
    @DisplayName("키의 수명은 TAT 까지다 — 지나면 처음 상태와 같아 남길 이유가 없다")
    void the_key_lives_until_its_tat() {
        // given
        UUID userId = UUID.randomUUID();
        acquire(userId);
        acquire(userId);
        acquire(userId);

        // when
        Duration ttl = redisTemplate.getExpire(key(userId)).block(VERIFY_CEILING);

        // then: 연달아 3건이면 TAT = 지금 + 9초
        assertThat(ttl).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(9));
    }

    @Test
    @DisplayName("TC#3·#8 — rate limit은 userId 기준 글로벌: 다른 유저는 독립")
    void limit_is_per_user() {
        // given
        UUID userA = UUID.randomUUID();
        for (int i = 0; i < RedisRateLimiter.BURST; i++) {
            acquire(userA);
        }

        // when & then
        assertThat(acquire(userA).allowed()).isFalse();
        assertThat(acquire(UUID.randomUUID()).allowed()).isTrue();
    }

    @Test
    @DisplayName("⭐ 옛 고정 창의 키(\"1\")가 남아 있어도 막히지 않는다 — 롤링 배포 중의 모양")
    void a_leftover_fixed_window_key_does_not_block() {
        // given: 옛 판정이 SET NX 로 남긴 값
        UUID userId = UUID.randomUUID();
        redisTemplate.opsForValue().set(key(userId), "1", Duration.ofSeconds(3)).block(VERIFY_CEILING);

        // when & then
        assertThat(acquire(userId).allowed()).isTrue();
        assertThat(storedTat(userId)).isGreaterThan(1L);
    }

    /**
     * TC#9 — Redis 장애 시 <b>첫 요청은</b> 허용한다. 장애 중 상한이 남는지는
     * {@code RedisRateLimiterDegradedTest} 가 본다.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("TC#9 — Redis 장애가 첫 요청을 막지 않는다 (상한은 로컬 판정으로 강등 — Degraded 테스트 참고)")
    void redis_failure_still_allows_the_first_request() {
        // given
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class);
        BDDMockito.given(broken.execute(ArgumentMatchers.any(RedisScript.class), ArgumentMatchers.anyList(),
                        ArgumentMatchers.anyList()))
                .willReturn((Flux) Flux.error(new RuntimeException("connection refused")));
        RedisRateLimiter failing = new RedisRateLimiter(broken, ChatMetrics.NOOP);

        // when
        RateLimitResult result = failing.tryAcquire(UUID.randomUUID()).block(VERIFY_CEILING);

        // then
        assertThat(result.allowed()).isTrue();
    }
}
