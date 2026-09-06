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

import com.sapari.chat.domain.exception.KickStoreCorruptedException;

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
        // given
        redisTemplate.opsForSet().add("chat:kicked:" + roomId, userId.toString()).block();

        // when & then
        assertThat(repository.isKicked(roomId, userId).block()).isTrue();
    }

    @Test
    @DisplayName("SET에 없는 userId는 false")
    void non_member_is_false() {
        // given
        redisTemplate.opsForSet().add("chat:kicked:" + roomId, UUID.randomUUID().toString()).block();

        // when & then
        assertThat(repository.isKicked(roomId, userId).block()).isFalse();
    }

    @Test
    @DisplayName("키 자체가 없으면 false (라이브 종료로 회수된 방)")
    void missing_key_is_false() {
        // when & then
        assertThat(repository.isKicked(UUID.randomUUID(), userId).block()).isFalse();
    }

    @Test
    @DisplayName("어댑터 계약 — Redis 장애 시 false로 흡수하지 않고 error를 전파한다(정책은 소비처 fail-open 몫; 삼키면 '조회 불가'와 '비강퇴'를 구분 못 함)")
    @SuppressWarnings("unchecked")
    void redis_failure_propagates_error_not_false() {
        // given
        // isMember는 (K,Object)/(K,Object...) 오버로드라 deep-stub은 매칭이 모호 → 명시적 mock으로 단일 오버로드 스텁
        ReactiveStringRedisTemplate broken = Mockito.mock(ReactiveStringRedisTemplate.class);
        ReactiveSetOperations<String, String> setOps = Mockito.mock(ReactiveSetOperations.class);
        BDDMockito.given(broken.opsForSet()).willReturn(setOps);
        // 실제 인자값 그대로 스텁 — isMember(K,Object)/(K,Object...) 오버로드 모호성 회피
        BDDMockito.given(setOps.isMember("chat:kicked:" + roomId, userId.toString()))
                .willReturn(Mono.error(new RuntimeException("connection refused")));

        // 타임아웃 있는 verify — 계약 위반(error 미전파) 시 무한 hang 대신 빠르게 실패

        // when & then
        StepVerifier.create(new ChatKickRedisRepository(broken).isKicked(roomId, userId))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(RuntimeException.class)
                        // 낫는 실패까지 오염으로 번역하면 갈라놓은 의미가 사라진다 — 소비처가 "사람이 와야
                        // 낫는다"고 로그하지만 실제로는 다음 요청에 복구되는 상황이 섞인다
                        .isNotInstanceOf(KickStoreCorruptedException.class))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("키가 SET이 아니면 KickStoreCorruptedException — 재시도로 낫는 실패와 타입부터 갈라 놓는다")
    void wrongType_isTranslatedToCorrupted() {
        // given: 다른 무언가가 같은 이름을 String으로 먼저 차지한 상태
        UUID room = UUID.randomUUID();
        String key = "chat:kicked:" + room;
        redisTemplate.opsForValue().set(key, "남의 값").block();

        // when & then: 어느 키를 사람이 치워야 하는지가 예외에 실려 나온다
        StepVerifier.create(repository.isKicked(room, userId))
                .expectErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(KickStoreCorruptedException.class)
                        .extracting(err -> ((KickStoreCorruptedException) err).getKey())
                        .isEqualTo(key))
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("오염된 키에도 종료 회수는 걸린다 — EXPIRE는 타입을 보지 않아 피해가 '그 방 + 24h'로 유계다")
    void expireAfterRoomEnded_appliesToPollutedKey() {
        // given: 강퇴 조회가 이미 불가능해진 방
        UUID room = UUID.randomUUID();
        String key = "chat:kicked:" + room;
        redisTemplate.opsForValue().set(key, "남의 값").block();

        // when
        StepVerifier.create(repository.expireAfterRoomEnded(room)).verifyComplete();

        // then: 오염이 영구히 남지는 않는다. 이 단언이 깨지면 방송이 끝나도 그 키가 그대로 남아
        // 같은 roomId가 다시 쓰일 때까지 회수되지 않는다는 뜻이다
        Duration ttl = redisTemplate.getExpire(key).block();
        assertThat(ttl).isNotNull().isBetween(Duration.ofHours(23), Duration.ofHours(24));
    }

    @Test
    @DisplayName("방 종료 회수 — 지우지 않고 만료를 건다. 멤버가 남아야 잘못된 종료 신호를 되돌릴 수 있다")
    void expireAfterRoomEnded_keepsMembersWithTtl() {
        // given: 강퇴 명단이 있는 방
        UUID room = UUID.randomUUID();
        UUID kicked = UUID.randomUUID();
        redisTemplate.opsForSet().add("chat:kicked:" + room, kicked.toString()).block();

        // when
        StepVerifier.create(repository.expireAfterRoomEnded(room)).verifyComplete();

        // then: DEL로 되돌아가면 멤버십이 사라져 이 단언이 깨진다
        StepVerifier.create(repository.isKicked(room, kicked)).expectNext(true).verifyComplete();
        Duration ttl = redisTemplate.getExpire("chat:kicked:" + room).block();
        assertThat(ttl).isNotNull().isBetween(Duration.ofHours(23), Duration.ofHours(24));
    }

    @Test
    @DisplayName("방 종료 회수 — 없는 키에 걸어도 무동작(어느 Pod가 몇 번 불러도 안전하다)")
    void expireAfterRoomEnded_isNoopOnMissingKey() {
        // given: 강퇴가 한 번도 없던 방
        UUID room = UUID.randomUUID();

        // when & then
        StepVerifier.create(repository.expireAfterRoomEnded(room)).verifyComplete();
        StepVerifier.create(redisTemplate.hasKey("chat:kicked:" + room)).expectNext(false).verifyComplete();
    }
}
