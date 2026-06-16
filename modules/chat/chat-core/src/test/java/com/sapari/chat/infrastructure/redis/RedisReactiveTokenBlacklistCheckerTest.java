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
class RedisReactiveTokenBlacklistCheckerTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static RedisReactiveTokenBlacklistChecker checker;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        checker = new RedisReactiveTokenBlacklistChecker(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

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
