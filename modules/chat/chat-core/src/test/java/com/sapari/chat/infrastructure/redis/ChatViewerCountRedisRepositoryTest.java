package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.global.time.TimeProvider;

/**
 * 방별 시청자 수가 채팅 접속 집계({@code ChatSessionRedisRepository.count})와 <b>같은 규칙</b>으로 세어지는지
 * 실제 Redis 에서 고정한다 — 고유 사용자, 낡은 세션 제외, 구분자 없는 옛 값은 살아 있는 것으로.
 */
@Testcontainers
@DisplayName("ChatViewerCountRepository — 채팅 접속 집계와 같은 규칙으로 센다")
class ChatViewerCountRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ChatViewerCountRedisRepository repository;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        repository = new ChatViewerCountRedisRepository(redisTemplate,
                new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("한 사람이 창을 여러 개 열어도 1명, 낡은 세션은 빼고, 옛 형식 값은 센다")
    void countsUniqueLiveUsers() {
        UUID room = UUID.randomUUID();
        long fresh = NOW.toEpochMilli();
        long stale = NOW.minus(ChatSessionRedisRepository.STALE_AFTER).minusSeconds(1).toEpochMilli();
        redisTemplate.opsForHash().putAll(ChatRedisKeys.sessions(room), Map.of(
                "s1", "alice:" + fresh,
                "s2", "alice:" + fresh,
                "s3", "bob:" + fresh,
                "s4", "carol:" + stale,
                "s5", "legacy-user"));

        assertThat(repository.countViewers(List.of(room))).containsExactly(Map.entry(room, 3L));
    }

    @Test
    @DisplayName("여러 방을 한 번에 세고, 세션이 없는 방은 0 으로 담는다")
    void countsSeveralRoomsAndZeroForEmpty() {
        UUID busy = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        redisTemplate.opsForHash().put(ChatRedisKeys.sessions(busy), "s1", "dave:" + NOW.toEpochMilli());

        assertThat(repository.countViewers(List.of(busy, empty)))
                .containsOnly(Map.entry(busy, 1L), Map.entry(empty, 0L));
    }

    @Test
    @DisplayName("빈 입력에는 Redis 를 부르지 않고 빈 맵")
    void emptyInput() {
        assertThat(repository.countViewers(List.of())).isEmpty();
    }
}
