package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

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
 * 종료 마커 어댑터 — 실제 Redis로 기록·조회·TTL을 확인한다.
 * (마커가 TTL 없이 남거나, TTL이 붙지 않아 영구히 남는 걸 잡는 자리)
 */
@Testcontainers
class ChatRoomEndedRedisRepositoryTest {


    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static ChatRoomEndedRedisRepository repository;

    private final UUID roomId = UUID.randomUUID();

    @BeforeAll
    static void startRedis() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        repository = new ChatRoomEndedRedisRepository(redisTemplate);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("마커 없음 — 진행 중인 방은 종료로 판정되지 않는다")
    void isEnded_falseWhenNotMarked() {
        // when & then
        assertThat(repository.isEnded(roomId).block(VERIFY_CEILING)).isFalse();
    }

    @Test
    @DisplayName("마커 기록 — 종료로 판정되고 TTL이 함께 걸린다(영구 잔존 방지)")
    void markEnded_setsMarkerWithTtl() {
        // given
        repository.markEnded(roomId).block(VERIFY_CEILING);

        // when & then
        assertThat(repository.isEnded(roomId).block(VERIFY_CEILING)).isTrue();
        Long ttl = redisTemplate.getExpire("chat:room:" + roomId + ":ended").block(VERIFY_CEILING).toSeconds();
        assertThat(ttl).isPositive();   // -1(무기한)이면 키가 영원히 남는다
    }

    @Test
    @DisplayName("중복 기록 — 여러 Pod가 같은 방을 동시에 처리해도 안전하다")
    void markEnded_isIdempotent() {
        // given
        repository.markEnded(roomId).block(VERIFY_CEILING);

        // when
        repository.markEnded(roomId).block(VERIFY_CEILING);

        // then
        assertThat(repository.isEnded(roomId).block(VERIFY_CEILING)).isTrue();
    }
}
