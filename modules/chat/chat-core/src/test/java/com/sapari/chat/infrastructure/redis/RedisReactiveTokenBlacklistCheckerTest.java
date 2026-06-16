package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.mongodb.representation.uuid=standard")
@Testcontainers
class RedisReactiveTokenBlacklistCheckerTest {

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Autowired
    private RedisReactiveTokenBlacklistChecker checker;

    @Autowired
    private ReactiveStringRedisTemplate redisTemplate;

    @Test
    @DisplayName("blacklist 등재 jti는 true — blocking write측(common/auth)과 같은 키를 읽는다")
    void blacklisted_jti_is_true() {
        String jti = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set("access-token:blacklist:" + jti, "1").block();

        assertThat(checker.isBlacklisted(jti).block()).isTrue();
    }

    @Test
    @DisplayName("미등재 jti는 false")
    void unknown_jti_is_false() {
        assertThat(checker.isBlacklisted(UUID.randomUUID().toString()).block()).isFalse();
    }
}
