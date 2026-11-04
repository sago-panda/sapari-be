package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 짧은 커맨드 상한이 <b>장수 패턴 구독을 죽이지 않는다.</b>
 *
 * <p>이 사실이 {@code RedisTimeoutConfig} 의 전제다 — 그 설정은 chat 의 <b>모든</b> Redis 호출에
 * 1초 상한을 걸고, 브로드캐스터의 {@code listenToPattern} 은 메시지 사이에 몇 분이고 조용할 수
 * 있다. 상한이 구독에도 걸린다면 그 설정이 <b>방마다 실시간 전달을 끊는다</b> — 고치려던 것보다
 * 훨씬 나쁘다. 그래서 전제를 고정한다.
 *
 * <p>Lettuce 의 커맨드 상한은 <b>응답을 기다리는 커맨드</b>에 걸린다. {@code PSUBSCRIBE} 는 구독
 * 확립 응답까지만 그 대상이고 그 뒤의 메시지 전달은 아니다 — 아래가 그것을 잰다.
 */
@Testcontainers
@DisplayName("Redis 구독 — 짧은 커맨드 상한에도 살아 있다")
class RedisSubscriptionSurvivesCommandTimeoutTest {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(1);

    /** 상한의 세 배를 조용히 보낸다 — 상한이 구독에 걸린다면 그 사이에 끊긴다. */
    private static final Duration SILENCE = COMMAND_TIMEOUT.multipliedBy(3);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Test
    @DisplayName("⭐ 상한의 세 배를 조용히 보낸 뒤 발행해도 수신된다 — 전역 상한이 실시간 전달을 끊지 않는다")
    void a_long_silence_does_not_break_the_subscription() {
        // given: 커맨드 상한을 짧게 둔 커넥션
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379)),
                LettuceClientConfiguration.builder().commandTimeout(COMMAND_TIMEOUT).build());
        factory.afterPropertiesSet();
        ReactiveStringRedisTemplate template = new ReactiveStringRedisTemplate(factory);

        List<String> received = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        try {
            template.listenToPattern("probe:pubsub:*")
                    .subscribe(message -> received.add(message.getMessage()),
                            error -> errors.add(error.getClass().getSimpleName()));
            awaitSubscription();

            // when: 상한의 세 배를 조용히 보낸 뒤 발행한다
            LockSupport.parkNanos(SILENCE.toNanos());
            template.convertAndSend("probe:pubsub:room", "after-silence").block(Duration.ofSeconds(10));

            // then
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (received.isEmpty() && System.nanoTime() - deadline < 0) {
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            assertThat(received)
                    .as("상한의 세 배를 조용히 보낸 뒤 발행한 메시지가 오지 않았다 — 전역 상한이 "
                            + "구독을 끊는다면 그 설정이 방마다 실시간 전달을 끊는다")
                    .containsExactly("after-silence");
            assertThat(errors).as("구독 스트림에 에러가 왔다").isEmpty();
        } finally {
            factory.destroy();
        }
    }

    /** 구독이 확립될 때까지 기다린다 — 확립 전 발행은 유실되므로(이 어댑터가 hot 스트림인 이유다). */
    private void awaitSubscription() {
        LockSupport.parkNanos(Duration.ofMillis(500).toNanos());
    }
}
