package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

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

@Testcontainers
class RedisChatKickRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static RedisChatKickRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        repository = new RedisChatKickRepository(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("kicked SET 멤버면 true (재접속 차단 판정)")
    void member_of_kicked_set_is_true() {
        redisTemplate.opsForSet().add("kicked:" + roomId, userId.toString()).block();

        assertThat(repository.isKicked(roomId, userId).block()).isTrue();
    }

    @Test
    @DisplayName("SET에 없는 userId는 false")
    void non_member_is_false() {
        redisTemplate.opsForSet().add("kicked:" + roomId, UUID.randomUUID().toString()).block();

        assertThat(repository.isKicked(roomId, userId).block()).isFalse();
    }

    @Test
    @DisplayName("키 자체가 없으면 false (라이브 종료로 정리된 방)")
    void missing_key_is_false() {
        assertThat(repository.isKicked(UUID.randomUUID(), userId).block()).isFalse();
    }
}
