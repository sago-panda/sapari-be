package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 탈퇴 신청 기록 — 쓴 것이 읽히고, <b>설정한 수명이 실제로 붙는지</b>를 실제 Redis 에서 고정한다.
 *
 * <p>수명이 이 기록의 계약이다 — 탈퇴 전에 발급된 룸 토큰보다 먼저 사라지면 그 토큰으로 다시 들어온다.
 * 수명 없는 SET 으로 바뀌면 영원히 남는 키가 되고, 너무 짧게 붙으면 덮어야 할 창보다 먼저 만료된다.
 */
@Testcontainers
@DisplayName("ChatWithdrawalRepository — 기록이 있으면 탈퇴 신청, 수명은 설정값")
class ChatWithdrawalRedisRepositoryTest {

    /** 반응형 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 멈추게 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private static final Duration TTL = Duration.ofHours(24);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static ChatWithdrawalRedisRepository repository;

    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        repository = new ChatWithdrawalRedisRepository(redisTemplate, TTL);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("기록 전에는 탈퇴 신청이 아니다")
    void absentMeansNotWithdrawing() {
        assertThat(repository.isWithdrawing(userId).block(VERIFY_CEILING)).isFalse();
    }

    @Test
    @DisplayName("⭐ 기록하면 읽히고, 설정한 수명이 붙는다")
    void aRecordIsReadBackWithTheConfiguredTtl() {
        repository.markWithdrawing(userId).block(VERIFY_CEILING);

        assertThat(repository.isWithdrawing(userId).block(VERIFY_CEILING)).isTrue();
        Duration ttl = redisTemplate.getExpire("chat:withdrawing:" + userId).block(VERIFY_CEILING);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(TTL).isGreaterThan(TTL.minusMinutes(1));
    }

    @Test
    @DisplayName("같은 기록을 여러 Pod 가 써도 하나다 — 모든 Pod 가 같은 이벤트를 받는다")
    void repeatedWritesAreHarmless() {
        repository.markWithdrawing(userId).block(VERIFY_CEILING);
        repository.markWithdrawing(userId).block(VERIFY_CEILING);

        assertThat(repository.isWithdrawing(userId).block(VERIFY_CEILING)).isTrue();
    }

    @Test
    @DisplayName("수명이 0 이하면 기동이 실패한다 — 즉시 사라지는 기록은 아무것도 막지 못한다")
    void aNonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> new ChatWithdrawalRedisRepository(redisTemplate, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat.withdrawal.record-ttl");
    }

    @Test
    @DisplayName("수명이 룸 토큰 수명 상한(300초)보다 짧으면 기동이 실패한다 — 탈퇴 전에 받은 토큰이 기록보다 오래 산다")
    void aTtlBelowTheRoomTokenCapIsRejected() {
        assertThatThrownBy(() -> new ChatWithdrawalRedisRepository(redisTemplate, Duration.ofSeconds(299)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chat.withdrawal.record-ttl");
    }

    @Test
    @DisplayName("수명이 정확히 300초면 기동한다")
    void aTtlAtTheRoomTokenCapIsAccepted() {
        new ChatWithdrawalRedisRepository(redisTemplate, Duration.ofSeconds(300));
    }
}
