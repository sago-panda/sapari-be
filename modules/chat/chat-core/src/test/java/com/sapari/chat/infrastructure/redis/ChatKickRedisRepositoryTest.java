package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.BDDMockito;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@Testcontainers
class ChatKickRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;
    private static ChatKickRedisRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
        repository = new ChatKickRedisRepository(redisTemplate);
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

    @Test
    @DisplayName("어댑터 계약 — Redis 장애 시 false로 흡수하지 않고 error를 전파한다(정책은 소비처 fail-open 몫; 삼키면 '조회 불가'와 '비강퇴'를 구분 못 함)")
    @SuppressWarnings("unchecked")
    void redis_failure_propagates_error_not_false() {
        // isMember는 (K,Object)/(K,Object...) 오버로드라 deep-stub은 매칭이 모호 → 명시적 mock으로 단일 오버로드 스텁
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveSetOperations<String, String> setOps = Mockito.mock(ReactiveSetOperations.class);
        BDDMockito.given(broken.opsForSet()).willReturn(setOps);
        // 실제 인자값 그대로 스텁 — isMember(K,Object)/(K,Object...) 오버로드 모호성 회피
        BDDMockito.given(setOps.isMember("kicked:" + roomId, userId.toString()))
                .willReturn(Mono.error(new RuntimeException("connection refused")));

        // 타임아웃 있는 verify — 계약 위반(error 미전파) 시 무한 hang 대신 빠르게 실패
        StepVerifier.create(new ChatKickRedisRepository(broken).isKicked(roomId, userId))
                .expectError(RuntimeException.class)
                .verify(Duration.ofSeconds(5));
    }
}
