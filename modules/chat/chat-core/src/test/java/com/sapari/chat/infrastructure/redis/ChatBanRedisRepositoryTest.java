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

import reactor.core.publisher.Mono;

/**
 * 밴 판정이 <b>키의 존재</b>로만 끝나는지 실제 Redis에서 고정한다.
 *
 * <p>이 설계의 핵심은 상태를 값에 담지 않는다는 것이다 — 기간 밴은 TTL이 지나면 키가 사라지고
 * 영구 밴은 TTL이 없다. 그래서 "만료됐는데 아직 밴으로 보이는" 창이 존재할 수 없다. 목으로는 그
 * 만료 동작 자체가 검증되지 않아 컨테이너를 띄운다.
 */
@Testcontainers
@DisplayName("ChatBanRepository — 키가 있으면 밴, 없으면 정상")
class ChatBanRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static ChatBanRedisRepository repository;

    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        repository = new ChatBanRedisRepository(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    private String key() {
        return "chat:banned:" + userId;
    }

    @Test
    @DisplayName("기간 밴 — TTL 붙은 키가 있으면 밴이다")
    void timedBanIsBanned() {
        // given
        redisTemplate.opsForValue().set(key(), "1", Duration.ofMinutes(10)).block();

        // when & then
        assertThat(repository.isBanned(userId).block()).isTrue();
    }

    @Test
    @DisplayName("영구 밴 — TTL 없는 키도 똑같이 밴이다")
    void permanentBanIsBanned() {
        // given: 영구 밴은 만료를 두지 않는다
        redisTemplate.opsForValue().set(key(), "1").block();

        // when & then
        assertThat(repository.isBanned(userId).block()).isTrue();
        assertThat(redisTemplate.getExpire(key()).block()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("키가 없으면 정상 유저 — 만료가 곧 해제다")
    void missingKeyIsNotBanned() {
        // when & then: 해제 절차가 따로 없다는 것이 이 설계의 요점이다
        assertThat(repository.isBanned(userId).block()).isFalse();
    }

    @Test
    @DisplayName("만료되면 그 순간부터 정상 — 상태를 값에 담지 않아 어긋날 창이 없다")
    void expiredBanBecomesNormal() {
        // given: 곧 사라질 밴
        redisTemplate.opsForValue().set(key(), "1", Duration.ofMillis(150)).block();
        assertThat(repository.isBanned(userId).block()).isTrue();

        // when: 만료를 기다린다
        waitForExpiry();

        // then
        assertThat(repository.isBanned(userId).block()).isFalse();
    }

    /** 만료를 기다린다 — 이 테스트가 확인하는 것이 Redis의 만료 동작 자체라 실제로 지나가야 한다. */
    private void waitForExpiry() {
        Mono.delay(Duration.ofMillis(400)).block();
    }

    @Test
    @DisplayName("값은 보지 않는다 — 존재만으로 판정한다")
    void valueIsIrrelevant() {
        // given: 값에 의미를 두면 그 값을 해석하는 코드가 양쪽에 생기고 갈라진다
        redisTemplate.opsForValue().set(key(), "무엇이든").block();

        // when & then
        assertThat(repository.isBanned(userId).block()).isTrue();
    }
}
