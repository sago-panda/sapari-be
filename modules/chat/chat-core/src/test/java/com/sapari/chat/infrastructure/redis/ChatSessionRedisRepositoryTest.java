package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 세션 HASH 어댑터 단위 통합 테스트 — 컨테이너 + 수동 조립.
 * 어댑터 1개만 검증하므로 @SpringBootTest(전체 컴포넌트 스캔=mongo 저장소까지 부트스트랩)를 쓰지 않는다.
 * TC 번호는 WebSocket 연결 표(§12.2) 중 sessions HASH 책임 항목.
 */
@Testcontainers
class ChatSessionRedisRepositoryTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redisTemplate;

    private ChatSessionRedisRepository repository;

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeAll
    static void startTemplate() {
        connectionFactory = new LettuceConnectionFactory(redis.getHost(), redis.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        redisTemplate = new ReactiveStringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stopTemplate() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        repository = new ChatSessionRedisRepository(redisTemplate);
        redisTemplate.delete("room:" + roomId + ":sessions").block();
    }

    @Test
    @DisplayName("TC#3 — 연결 시 sessions HASH에 sessionId→userId가 등록된다")
    void add_registers_session_to_userId_mapping() {
        // given
        repository.add(roomId, "session-1", userId).block();

        Object stored = redisTemplate.opsForHash()
                .get("room:" + roomId + ":sessions", "session-1").block();

        // when & then
        assertThat(stored).isEqualTo(userId.toString());
    }

    @Test
    @DisplayName("TC#4 — 유저 1명 1탭이면 count=1")
    void single_user_single_tab_counts_one() {
        // given
        repository.add(roomId, "session-1", userId).block();

        // when & then
        assertThat(repository.count(roomId).block()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC#5·#24 — 같은 유저 3탭이어도 count=1 (고유 유저 수, 탭 수 아님)")
    void multi_tab_same_user_counts_one() {
        // given
        repository.add(roomId, "tab-1", userId).block();
        repository.add(roomId, "tab-2", userId).block();
        repository.add(roomId, "tab-3", userId).block();

        // when & then
        assertThat(repository.count(roomId).block()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 유저 2명이면 count=2")
    void distinct_users_count_independently() {
        // given
        repository.add(roomId, "s1", userId).block();
        repository.add(roomId, "s2", UUID.randomUUID()).block();

        // when & then
        assertThat(repository.count(roomId).block()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC#6·#7 — 종료 시 sessionId가 제거되고 count가 감소한다")
    void remove_deletes_session_and_decreases_count() {
        // given
        repository.add(roomId, "s1", userId).block();
        repository.add(roomId, "s2", UUID.randomUUID()).block();

        repository.remove(roomId, "s1").block();

        // when & then
        assertThat(repository.count(roomId).block()).isEqualTo(1);
        assertThat(redisTemplate.opsForHash()
                .hasKey("room:" + roomId + ":sessions", "s1").block()).isFalse();
    }

    @Test
    @DisplayName("TC#25 — 멀티탭 중 한 탭 제거 시 나머지 탭 유지, count 불변")
    void removing_one_tab_keeps_other_tabs() {
        // given
        repository.add(roomId, "tab-1", userId).block();
        repository.add(roomId, "tab-2", userId).block();

        repository.remove(roomId, "tab-1").block();

        // when & then
        assertThat(redisTemplate.opsForHash()
                .hasKey("room:" + roomId + ":sessions", "tab-2").block()).isTrue();
        assertThat(repository.count(roomId).block()).isEqualTo(1);
    }

    @Test
    @DisplayName("clearRoom — 키 자체가 삭제된다 (라이브 종료 정리)")
    void clearRoom_deletes_the_hash_key() {
        // given
        repository.add(roomId, "s1", userId).block();

        repository.clearRoom(roomId).block();

        // when & then
        assertThat(redisTemplate.hasKey("room:" + roomId + ":sessions").block()).isFalse();
        assertThat(repository.count(roomId).block()).isZero();
    }

    @Test
    @DisplayName("add — 등재와 함께 TTL이 실제로 걸린다(백스톱이 사라지는 걸 목이 아니라 Redis로 확인)")
    void add_setsTtlOnRealRedis() {
        // when
        repository.add(roomId, "s1", userId).block();

        // then: -1(무기한)이면 정상 회수가 다 실패했을 때 키를 받아줄 폴백이 사라진다
        assertThat(redisTemplate.getExpire("room:" + roomId + ":sessions").block().toSeconds()).isPositive();
    }
}
