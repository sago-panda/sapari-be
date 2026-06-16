package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@SpringBootTest(properties = "spring.mongodb.representation.uuid=standard")
@Testcontainers
class RedisChatKickRepositoryTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    private RedisChatKickRepository repository;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

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
