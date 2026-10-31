package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.global.time.TimeProvider;

/**
 * 좀비 세션이 시청자 수를 부풀리지 않는지 본다 — 실제 Redis 로.
 *
 * <p><b>왜 필요한가.</b> Pod 가 크래시하면 그 Pod 의 세션은 해제 경로({@code remove})를 타지 못해
 * 명부에 남는다. 그걸 그대로 세면 그 방의 시청자 수가 <b>방송 내내</b> 부풀어 있고, 유일한 회수
 * 수단이 키 전체 TTL(24시간)이라 방송이 끝날 때까지 틀린 수가 보인다.
 *
 * <p><b>그리고 재등록은 TTL 터치가 아니다.</b> 정본이 그걸 못 박아 뒀다 — 터치만 하면 Redis 데이터
 * 유실(replica 승격 등) 뒤에 사라진 항목이 되살아나지 않아 수가 모자란 채로 간다. 그 요구를 재는
 * 방법은 <b>항목을 지운 뒤 재등록하는 것</b>이고, 터치 구현은 그 테스트를 통과하지 못한다.
 *
 * <p>시계를 합성한다 — 낡음 상한이 2분이라 실시간으로 기다리면 테스트가 2분짜리가 된다.
 */
@Testcontainers
@DisplayName("세션 명부 — 좀비는 세지 않고, 재등록은 항목을 되살린다")
class ChatSessionHeartbeatTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    private static final Instant T0 = Instant.parse("2026-06-18T00:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    private LettuceConnectionFactory factory;
    private ReactiveStringRedisTemplate template;
    private ChatSessionRedisRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new ReactiveStringRedisTemplate(factory);
        template.delete(ChatRedisKeys.sessions(roomId)).block(VERIFY_CEILING);
        // 합성 시계 — 낡음 상한이 2분이라 실시간으로 기다리면 테스트가 2분짜리가 된다.
        // TimeProvider 를 감싸지 않고 <b>Clock 을 움직인다</b> — 운영이 쓰는 경로가 그쪽이라,
        // TimeProvider 를 가짜로 바꾸면 그 안의 변환이 검증에서 빠진다.
        Clock movingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        repository = new ChatSessionRedisRepository(template, new TimeProvider(movingClock));
    }

    private long count() {
        Long value = repository.count(roomId).block(VERIFY_CEILING);
        return value == null ? -1 : value;
    }

    @Test
    @DisplayName("방금 등재한 세션은 세어진다")
    void aFreshSessionCounts() {
        // given
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);

        // when & then
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 재등록이 끊긴 좀비는 세지 않는다 — 그대로 세면 방송 내내 시청자 수가 부푼다")
    void aZombieEntryIsNotCounted() {
        // given: 두 사람이 들어왔고
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);
        repository.add(roomId, "s2", userB).block(VERIFY_CEILING);
        assertThat(count()).isEqualTo(2);

        // when: 시간이 상한을 넘고, 한 쪽만 재등록한다(다른 쪽 Pod 가 죽었다)
        now.set(T0.plus(repository.heartbeatInterval().multipliedBy(5)));
        repository.heartbeat(roomId, "s1", userA).block(VERIFY_CEILING);

        // then
        assertThat(count()).as("좀비가 집계에 남았다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 재등록 2회를 놓쳐도 살아 있는 세션은 남는다 — 상한이 주기의 4배인 이유")
    void twoMissedHeartbeatsDoNotDropALiveSession() {
        // given
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);

        // when: 주기 3개가 지났다(= 재등록 2회 유실)
        now.set(T0.plus(repository.heartbeatInterval().multipliedBy(3)));

        // then: Redis 가 잠깐 흔들린 순간이 정확히 이 모양이다
        assertThat(count()).as("한두 번 놓친 세션이 곧바로 집계에서 빠졌다").isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 재등록은 지워진 항목을 되살린다 — TTL 터치 구현은 이 단언을 통과하지 못한다")
    void theHeartbeatRestoresALostEntry() {
        // given: 등재 후 Redis 데이터 유실(replica 승격 등)을 흉내 내 항목만 지운다
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);
        template.opsForHash().remove(ChatRedisKeys.sessions(roomId), "s1").block(VERIFY_CEILING);
        assertThat(count()).isZero();

        // when
        repository.heartbeat(roomId, "s1", userA).block(VERIFY_CEILING);

        // then
        assertThat(count()).as("재등록이 항목을 되살리지 못했다 — 그 방송 내내 수가 모자란다").isEqualTo(1);
    }

    @Test
    @DisplayName("재등록이 키 TTL 도 되살린다 — 만료 조건이 \"마지막 입장 이후\"에 묶이지 않게")
    void theHeartbeatRefreshesTheKeyTtl() {
        // given
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);
        template.expire(ChatRedisKeys.sessions(roomId), Duration.ofSeconds(5)).block(VERIFY_CEILING);

        // when
        repository.heartbeat(roomId, "s1", userA).block(VERIFY_CEILING);

        // then
        Duration remaining = template.getExpire(ChatRedisKeys.sessions(roomId)).block(VERIFY_CEILING);
        assertThat(remaining).as("TTL 이 갱신되지 않았다 — 한산한 방이 방송 중에 만료된다")
                .isGreaterThan(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("⭐ 멀티탭은 여전히 1 이다 — 시청자 수는 사람 수지 창 수가 아니다")
    void multipleTabsStillCountAsOne() {
        // given
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);
        repository.add(roomId, "s2", userA).block(VERIFY_CEILING);

        // when & then
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 구 형식(타임스탬프 없음)은 살아 있는 것으로 본다 — 롤링 배포 중 전원이 사라지지 않게")
    void legacyValuesAreTreatedAsLive() {
        // given: 구 버전 Pod 가 쓴 모양 — userId 만
        template.opsForHash().put(ChatRedisKeys.sessions(roomId), "s-old", userB.toString())
                .block(VERIFY_CEILING);
        now.set(T0.plus(Duration.ofHours(1)));

        // then: 낡은 것으로 치면 배포 중에 그 Pod 의 시청자 전원이 집계에서 사라진다
        assertThat(count()).isEqualTo(1);
    }

    @Test
    @DisplayName("퇴장은 그대로 항목을 지운다")
    void removalStillDeletesTheEntry() {
        // given
        repository.add(roomId, "s1", userA).block(VERIFY_CEILING);

        // when
        repository.remove(roomId, "s1").block(VERIFY_CEILING);

        // then
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("재등록 주기가 계약으로 노출된다 — 스케줄러가 이 값을 따라야 한다")
    void theIntervalIsPartOfTheContract() {
        assertThat(repository.heartbeatInterval()).isPositive();
    }
}
