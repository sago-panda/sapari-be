package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;

import com.sapari.chat.application.port.RateLimitResult;

import reactor.core.publisher.Mono;

/**
 * Redis 가 죽은 구간의 레이트리밋 — 컨테이너를 띄우지 않는다.
 *
 * <p>장애를 재려는 것이라 진짜 Redis 가 필요 없고, 끊어진 주소로 붙이면 실패하기까지의 시간이 환경에
 * 달려 테스트가 느리고 불안정해진다. 여기서 재는 것은 "연산이 실패했을 때 무엇을 돌려주는가" 하나다.
 */
@DisplayName("RedisRateLimiter — Redis 장애 구간")
class RedisRateLimiterDegradedTest {

    private RedisRateLimiter rateLimiter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUpFailingRedis() {
        ReactiveStringRedisTemplate template = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> operations = mock(ReactiveValueOperations.class);
        given(template.opsForValue()).willReturn(operations);
        given(operations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(Mono.error(new RedisConnectionFailureException("프로브 — Redis 가 죽었다")));
        rateLimiter = new RedisRateLimiter(template);
    }

    @Test
    @DisplayName("⭐ 장애 중에도 상한이 남는다 — 첫 요청은 허용하되 창 안의 두 번째는 거부한다")
    void keeps_a_cap_while_redis_is_down() {
        // given
        UUID userId = UUID.randomUUID();

        // when
        RateLimitResult first = rateLimiter.tryAcquire(userId).block();
        RateLimitResult second = rateLimiter.tryAcquire(userId).block();

        // then: 전면 통과였다면 회선 속도로 미는 클라 하나가 창 없이 전부 통과하고, 그 프레임마다
        // 저장이 일어난다. 가용성을 지키려 허용하는 것이 "무제한 허용"일 이유는 없다.
        assertThat(first.allowed()).as("장애 중 첫 요청은 허용 — 가용성이 엄격성보다 우선").isTrue();
        assertThat(second.allowed()).as("창 안의 두 번째는 거부 — 장애가 상한을 지우지 않는다").isFalse();
        assertThat(second.retryAfterSeconds()).isBetween(1L, 3L);
    }

    @Test
    @DisplayName("장애 중 상한은 userId 단위다 — 한 사람이 막혀도 남이 막히지 않는다")
    void cap_is_per_user() {
        // given
        UUID blocked = UUID.randomUUID();
        rateLimiter.tryAcquire(blocked).block();

        // when & then
        assertThat(rateLimiter.tryAcquire(blocked).block().allowed()).isFalse();
        assertThat(rateLimiter.tryAcquire(UUID.randomUUID()).block().allowed()).isTrue();
    }

    @Test
    @DisplayName("⭐ 거부가 창을 연장하지 않는다 — 막힌 채로 계속 밀어도 해제가 미뤄지지 않는다")
    void denial_does_not_extend_the_window() {
        // given: 창을 열고 그 안에서 2초 동안 계속 두드린다
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        long window = Duration.ofSeconds(3).toNanos();
        rateLimiter.localWindow(userId, start);
        for (long at = start; at < start + Duration.ofSeconds(2).toNanos(); at += 100_000_000L) {
            assertThat(rateLimiter.localWindow(userId, at).allowed()).isFalse();
        }

        // when & then: 처음 열린 창의 끝을 막 지난 시점
        //
        // 거부가 창을 밀면 마지막 두드림(start + 1.9s)이 끝을 start + 4.9s 로 옮겨 여기서도 거부다 —
        // 계속 두드리는 클라가 스스로 영구 차단된다. retryAfterSeconds 로는 이걸 잴 수 없다:
        // 초 단위 올림이라 연장 전후가 똑같이 3 으로 나와 가려진다(실측 — 이 단언을 초로 쓰면 뮤턴트가 생존).
        assertThat(rateLimiter.localWindow(userId, start + window + 1).allowed())
                .as("처음 열린 창이 지났으면 허용 — 두드림이 창을 밀지 않는다").isTrue();
    }

    @Test
    @DisplayName("장애 중에도 창이 지나면 다시 허용된다 — 강등이 영구 차단이 아니다")
    void window_reopens_after_it_passes() {
        // given
        UUID userId = UUID.randomUUID();
        long start = System.nanoTime();
        long afterWindow = start + Duration.ofSeconds(3).toNanos() + 1;

        // when & then
        assertThat(rateLimiter.localWindow(userId, start).allowed()).isTrue();
        assertThat(rateLimiter.localWindow(userId, start + 1).allowed()).isFalse();
        assertThat(rateLimiter.localWindow(userId, afterWindow).allowed())
                .as("창이 지났으면 다시 허용 — 그렇지 않으면 장애 한 번이 영구 차단이 된다").isTrue();
    }

    @Test
    @DisplayName("⭐ 장애가 끝나면 추적 항목이 풀린다 — 창은 3초면 쓸모없는데 스스로 사라지지 않는다")
    void expired_windows_are_released() {
        // given: 서로 다른 사용자 셋이 장애 구간에 전송했다
        long start = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            rateLimiter.localWindow(UUID.randomUUID(), start);
        }
        assertThat(rateLimiter.trackedLocalWindows()).isEqualTo(3);

        // when: 창이 지난 뒤 누군가 한 번 더 전송한다
        long afterWindow = start + Duration.ofSeconds(3).toNanos() + 1;
        rateLimiter.localWindow(UUID.randomUUID(), afterWindow);

        // then: 지난 셋은 풀리고 방금 것만 남는다. 안 쓸면 장애가 길어질수록 항목이 쌓이고
        // 장애가 끝난 뒤에도 그대로 남아 이 Pod 가 사는 동안 회수되지 않는다.
        assertThat(rateLimiter.trackedLocalWindows()).isEqualTo(1);
    }
}
