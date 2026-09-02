package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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

/**
 * 강퇴 등록이 <b>명단을 만료에서 건져내는지</b>를 실제 Redis에서 고정한다.
 *
 * <p>이 보장은 애플리케이션 코드가 아니라 Redis의 동작에서 온다 — {@code SADD}는 기존 만료를 건드리지
 * 않는다. 목으로는 그 사실 자체가 검증되지 않아 컨테이너를 띄운다.
 *
 * <p>왜 이게 중요한가: 방 종료 신호가 잘못 발행되면 그 방의 강퇴 명단에 회수용 만료(24h)만 붙는다.
 * 방송은 계속되므로 그 뒤에도 강퇴가 일어나는데, 만료를 떼지 않으면 그때 올린 사람까지 함께
 * <b>24시간 뒤에 조용히 사라진다</b>. 명단이 비면 강퇴됐던 사람이 전원 돌아오고, 그 사실을 알려주는
 * 신호는 어디에도 없다.
 */
@Testcontainers
@DisplayName("ChatKickWriteRepository — 등록은 명단의 만료를 함께 걷어낸다")
class ChatKickWriteRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ChatKickWriteRedisRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        repository = new ChatKickWriteRedisRepository(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    private String key() {
        return "chat:kicked:" + roomId;
    }

    @Test
    @DisplayName("등록하면 명단에 올라간다 — 읽는 쪽이 보는 키와 같은 키다")
    void registeredUserIsInTheSet() {
        // when
        repository.register(roomId, userId);

        // then: 키 이름이 어긋나면 쓰기는 성공해도 입장 게이트가 영영 통과시킨다
        assertThat(redisTemplate.opsForSet().isMember(key(), userId.toString())).isTrue();
    }

    @Test
    @DisplayName("같은 사람을 다시 올려도 명단은 하나 — 재시도가 안전하다")
    void registeringTwiceKeepsOneMember() {
        // given
        repository.register(roomId, userId);

        // when
        repository.register(roomId, userId);

        // then
        assertThat(redisTemplate.opsForSet().size(key())).isEqualTo(1);
    }

    @Test
    @DisplayName("만료가 붙어 있던 명단에 등록하면 만료가 사라진다 — PERSIST를 빼면 이 단언이 깨진다")
    void registrationClearsExpiryLeftByRoomEnded() {
        // given: 방 종료 신호가 잘못 와서 회수용 만료만 붙은 상태
        redisTemplate.opsForSet().add(key(), UUID.randomUUID().toString());
        redisTemplate.expire(key(), Duration.ofHours(24));
        assertThat(redisTemplate.getExpire(key())).isPositive();

        // when: 방송이 계속돼 새 강퇴가 올라온다
        repository.register(roomId, userId);

        // then: -1 = 만료 없음. 그대로 두면 이 명단은 24시간 뒤 통째로 사라진다
        assertThat(redisTemplate.getExpire(key())).isEqualTo(-1L);
        assertThat(redisTemplate.opsForSet().size(key())).isEqualTo(2);
    }

    @Test
    @DisplayName("만료가 없던 명단은 그대로 만료 없음 — 평시에 없던 것을 만들지 않는다")
    void registrationLeavesUnexpiringSetAlone() {
        // when
        repository.register(roomId, userId);

        // then
        assertThat(redisTemplate.getExpire(key())).isEqualTo(-1L);
    }
}
