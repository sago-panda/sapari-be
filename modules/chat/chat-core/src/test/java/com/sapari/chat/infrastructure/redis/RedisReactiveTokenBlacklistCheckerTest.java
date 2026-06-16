package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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

    @Test
    @DisplayName("어댑터 계약 — Redis 장애 시 false로 흡수하지 않고 error를 전파한다(정책은 소비처 fail-open 몫; 삼키면 '조회 불가'와 '미등재'를 구분 못 함)")
    void redis_failure_propagates_error_not_false() {
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class);
        Mockito.when(broken.hasKey(Mockito.anyString()))
                .thenReturn(Mono.error(new RuntimeException("connection refused")));

        StepVerifier.create(new RedisReactiveTokenBlacklistChecker(broken).isBlacklisted("some-jti"))
                .expectError(RuntimeException.class)
                .verify(Duration.ofSeconds(5));
    }
}
