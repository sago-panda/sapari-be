package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 밴 미러 쓰기가 <b>줄어들지 않는지</b>를 실제 Redis에서 고정한다.
 *
 * <p>이 보장이 필요한 이유는 하나다. 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출자가 각각 밴을
 * 만들고 각자 미러에 쓴다. 무조건 덮어쓰면 <b>나중에 도착한 쪽이 이기므로</b> 짧은 TTL이 남을 수 있고,
 * 집행은 미러가 하므로 그 사람은 정본에 한 달이 남아 있어도 일주일 뒤에 돌아온다.
 *
 * <p><b>도착 순서를 바꿔 가며 확인한다.</b> 정본을 다시 읽어 가장 긴 것을 쓰는 방식도 시도했지만 그건
 * "무엇을 쓸지"만 고치고 "누가 마지막에 쓰는지"는 그대로여서 레이스를 닫지 못했다. 그래서 여기서 재는
 * 것은 값이 아니라 <b>순서 독립</b>이다 — 어느 쪽이 나중에 도착해도 결과가 같아야 한다.
 *
 * <p>비교와 쓰기가 한 실행 안에 있는지는 목으로 검증되지 않아 컨테이너를 띄운다.
 */
@Testcontainers
@DisplayName("ChatBanWriteRepository — 미러는 늘어나기만 한다")
class ChatBanWriteRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static ChatBanWriteRedisRepository repository;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration MONTH = Duration.ofDays(30);

    private UUID userId;

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        repository = new ChatBanWriteRedisRepository(redisTemplate);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void freshUser() {
        userId = UUID.randomUUID();
    }

    private String key() {
        return "chat:banned:" + userId;
    }

    private void ban(Duration length) {
        repository.ban(userId, NOW.plus(length), NOW);
    }

    private void banForever() {
        repository.ban(userId, null, NOW);
    }

    /** 남은 TTL. 만료 없음은 -1, 키 없음은 -2 — Redis의 규약을 그대로 쓴다. */
    private long ttlMillis() {
        Long ttl = redisTemplate.getExpire(key(), java.util.concurrent.TimeUnit.MILLISECONDS);
        return ttl == null ? -2 : ttl;
    }

    @Test
    @DisplayName("없던 키에 밴을 걸면 그 만료가 붙는다")
    void firstBanSetsTheExpiry() {
        // when
        ban(WEEK);

        // then
        assertThat(ttlMillis()).isCloseTo(WEEK.toMillis(), org.assertj.core.data.Offset.offset(2_000L));
    }

    @Test
    @DisplayName("⭐ 긴 밴 뒤에 짧은 밴이 와도 줄어들지 않는다 — 동시 강퇴에서 늦게 도착하는 쪽이 짧을 수 있다")
    void shorterBanDoesNotShortenAnExistingOne() {
        // given: 한 달짜리가 먼저 자리를 잡았다
        ban(MONTH);

        // when: 일주일짜리가 뒤에 도착한다
        ban(WEEK);

        // then: 한 달이 그대로 남는다. 줄어들면 그 사용자는 23일 일찍 돌아온다
        assertThat(ttlMillis())
                .as("짧은 밴이 긴 밴을 덮었다 — 미러가 정본보다 일찍 풀린다")
                .isGreaterThan(MONTH.toMillis() - 2_000L);
    }

    @Test
    @DisplayName("⭐ 반대 순서로 도착해도 결과가 같다 — 순서에 기대지 않는다는 것이 이 장치의 전부다")
    void resultIsIndependentOfArrivalOrder() {
        // given & when: 짧은 것 먼저, 긴 것 나중
        ban(WEEK);
        ban(MONTH);

        // then: 위 테스트와 같은 결과 — 어느 쪽이 먼저 와도 한 달이다
        assertThat(ttlMillis()).isGreaterThan(MONTH.toMillis() - 2_000L);
    }

    @Test
    @DisplayName("⭐ 영구 밴은 어떤 기한부 밴도 덮지 못한다 — 만료 없음이 가장 긴 만료다")
    void permanentBanIsNeverOverwritten() {
        // given
        banForever();

        // when
        ban(MONTH);

        // then: -1은 만료 없음이다
        assertThat(ttlMillis()).isEqualTo(-1);
    }

    @Test
    @DisplayName("기한부 밴은 영구로 승격된다 — 위로 가는 방향은 열려 있다")
    void timedBanCanBePromotedToPermanent() {
        // given
        ban(WEEK);

        // when
        banForever();

        // then
        assertThat(ttlMillis()).isEqualTo(-1);
    }

    @Test
    @DisplayName("이미 지난 만료는 아무것도 쓰지 않는다 — 음수 TTL은 거부되고, 정본에 없는 상태다")
    void expiredBanWritesNothing() {
        // when: 만료가 현재보다 과거다
        repository.ban(userId, NOW.minus(Duration.ofDays(1)), NOW);

        // then: 키 자체가 생기지 않는다
        assertThat(redisTemplate.hasKey(key())).isFalse();
    }

    @Test
    @DisplayName("이미 지난 만료가 기존 밴을 지우지도 않는다")
    void expiredBanDoesNotClearAnExistingOne() {
        // given
        ban(MONTH);

        // when
        repository.ban(userId, NOW.minus(Duration.ofDays(1)), NOW);

        // then
        assertThat(ttlMillis()).isGreaterThan(MONTH.toMillis() - 2_000L);
    }
}
