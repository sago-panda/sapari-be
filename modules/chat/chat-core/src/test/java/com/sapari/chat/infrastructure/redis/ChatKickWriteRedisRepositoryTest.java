package com.sapari.chat.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.sapari.chat.domain.exception.KickStoreCorruptedException;

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
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

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
    @DisplayName("키가 우리 타입이 아니면 낫지 않는 실패로 갈라 던진다 — 재시도는 멱등이면서 영원히 실패한다")
    void wrongTypeKeyIsReportedAsUnrecoverable() {
        // given: 누가 같은 이름을 String으로 선점했다
        redisTemplate.opsForValue().set(key(), "someone-elses-value");

        // when & then: 일시 장애와 같은 예외로 두면 사람이 그 키를 치울 때까지 조용히 계속 실패한다
        assertThatThrownBy(() -> repository.register(roomId, userId))
                .isInstanceOf(KickStoreCorruptedException.class)
                .satisfies(e -> assertThat(((KickStoreCorruptedException) e).getKey()).isEqualTo(key()));
    }

    @Test
    @DisplayName("만료가 없던 명단은 그대로 만료 없음 — 평시에 없던 것을 만들지 않는다")
    void registrationLeavesUnexpiringSetAlone() {
        // when
        repository.register(roomId, userId);

        // then
        assertThat(redisTemplate.getExpire(key())).isEqualTo(-1L);
    }

    @Test
    @DisplayName("⭐ 재적재는 사라진 명단을 정본에서 되살린다 — 만료도 붙지 않는다")
    void reconcileRestoresALostList() {
        // given: 키가 축출·승격으로 사라진 상태
        UUID other = UUID.randomUUID();
        assertThat(redisTemplate.hasKey(key())).isFalse();

        // when
        repository.reconcile(roomId, java.util.List.of(userId, other));

        // then
        assertThat(redisTemplate.opsForSet().members(key()))
                .containsExactlyInAnyOrder(userId.toString(), other.toString());
        assertThat(redisTemplate.getExpire(key()))
                .as("만료가 붙으면 되살린 명단이 그 시각에 다시 조용히 사라진다")
                .isEqualTo(-1L);
    }

    /**
     * ⭐ 이 테스트가 재적재의 존재 이유다.
     *
     * <p>키가 우리 타입이 아니면 읽기도 쓰기도 Redis 가 멀쩡해도 계속 실패한다 — 재시도는 멱등이면서
     * <b>영원히 실패</b>한다({@code KickStoreCorruptedException}). 읽기 경로는 fail-open 이라 그 방의
     * 강퇴자 전원이 돌아오고 로그 한 줄만 남는다. {@code SADD} 로는 고칠 수 없다(타입이 틀려 추가
     * 자체가 실패) — 그래서 스크립트가 {@code DEL} 로 시작한다.
     */
    @Test
    @DisplayName("⭐ 오염된 키(WRONGTYPE)도 재적재로 고쳐진다 — SADD 로는 못 고치는 자리다")
    void reconcileRepairsACorruptedKey() {
        // given: 집합이 아닌 타입으로 오염
        redisTemplate.opsForValue().set(key(), "오염된 문자열");
        assertThatThrownBy(() -> repository.register(roomId, userId))
                .as("전제가 성립해야 이 테스트가 의미를 갖는다 — 추가로는 고칠 수 없어야 한다")
                .isInstanceOf(KickStoreCorruptedException.class);

        // when
        boolean wasCorrupted = repository.reconcile(roomId, java.util.List.of(userId));

        // then
        assertThat(wasCorrupted)
                .as("오염이었다는 사실을 돌려주지 않으면 TYPE 읽기를 DEL 뒤로 옮겨도 아무것도 안 깨진다 — "
                        + "그 값이 ADMIN 전용의 근거(가청성)를 지탱한다")
                .isTrue();
        assertThat(redisTemplate.type(key())).isEqualTo(org.springframework.data.redis.connection.DataType.SET);
        assertThat(redisTemplate.opsForSet().isMember(key(), userId.toString())).isTrue();
        // 고쳐진 뒤에는 평소 경로가 다시 돈다
        repository.register(roomId, UUID.randomUUID());
        assertThat(redisTemplate.opsForSet().size(key())).isEqualTo(2);
    }

    /**
     * ⭐ <b>건강한 키에는 더하기만 한다</b> — 한때 이것이 "교체" 였고 그게 결함이었다.
     *
     * <p>처음에 {@code DEL} 을 무조건 맨 앞에 뒀다. 강퇴가 DB-first 라(로그 커밋 → {@code SADD})
     * 이런 끼어들기가 열렸다 — 재적재가 정본을 읽은 <i>뒤</i> 커밋된 강퇴는 그 재적재의
     * {@code DEL} 에 지워지고, 읽기 경로가 fail-open 이라 아무도 막히지 않으며 재시도 경로가 없어
     * <b>영구</b>다. 복구하려 부른 조치가 집행을 지운다.
     *
     * <p>더하기가 충분한 이유는 집합의 방향이 한쪽이기 때문이다 — 멤버를 넣는 것은 강퇴뿐이고
     * 강퇴는 로그를 먼저 커밋하므로 앱 경로에서 {@code SET ⊆ 정본} 이 늘 참이다. 그러니 재적재가
     * 지워야 할 것은 없다. 반대 방향(정본에 없는데 SET 에 있음)을 만드는 경로가 없다.
     */
    @Test
    @DisplayName("⭐ 건강한 키에는 더하기만 한다 — 동시 강퇴를 지우지 않는다")
    void reconcileOnlyAddsToAHealthyKey() {
        // given: 재적재가 정본을 읽은 뒤에 커밋된 강퇴가 이미 SADD 돼 있다
        UUID concurrentKick = UUID.randomUUID();
        repository.register(roomId, concurrentKick);

        // when: 정본 스냅샷에는 그 사람이 없다(읽기 시점에 미커밋이었다)
        repository.reconcile(roomId, java.util.List.of(userId));

        // then
        assertThat(redisTemplate.opsForSet().members(key()))
                .as("지우면 204 를 받은 강퇴가 조용히·영구히 무효화된다")
                .containsExactlyInAnyOrder(userId.toString(), concurrentKick.toString());
    }

    @Test
    @DisplayName("정본이 비면 기존 명단을 건드리지 않는다 — 비었다는 것이 '지워라' 가 아니다")
    void anEmptySourceOfTruthLeavesTheKeyAlone() {
        // given
        repository.register(roomId, userId);

        // when: 정본이 비었다 — 그 방에 강퇴가 없거나, 읽은 시점에 아직 없었다
        repository.reconcile(roomId, java.util.List.of());

        // then: 둘을 구분할 수 없으므로 지우지 않는다(위 더하기 전용 결정과 같은 근거)
        assertThat(redisTemplate.opsForSet().members(key())).containsExactly(userId.toString());
    }

    @Test
    @DisplayName("⭐ 오염된 키에서만 지운다 — 거기서는 잃을 내용이 애초에 없다")
    void onlyACorruptedKeyIsDeleted() {
        // given
        redisTemplate.opsForValue().set(key(), "오염된 문자열");

        // when
        boolean wasCorrupted = repository.reconcile(roomId, java.util.List.of(userId));

        // then: 그 키의 내용은 우리가 읽을 수 없는 값이었으므로 지워도 잃는 것이 없다
        assertThat(wasCorrupted).isTrue();
        assertThat(redisTemplate.opsForSet().members(key())).containsExactly(userId.toString());
    }

    @Test
    @DisplayName("건강한 키였으면 오염 아님을 돌려준다 — 두 값이 갈려야 신호가 된다")
    void reconcileReportsNoCorruptionForAHealthyKey() {
        // given
        repository.register(roomId, userId);

        // when & then
        assertThat(repository.reconcile(roomId, java.util.List.of(userId)))
                .as("항상 true 를 돌려주면 '조치가 필요했는가' 를 가르지 못한다")
                .isFalse();
    }

    /**
     * ⭐ 청크 경계를 넘는 벡터 — 이것이 없으면 쪼갠 루프에 가드가 0이다.
     *
     * <p>커밋된 다른 벡터의 최대 멤버 수가 <b>2</b> 였다. 되돌림 측정: 루프를
     * {@code unpack(ARGV, 2, #ARGV)}(쪼개기 이전 판)으로 바꿔도 11/11 초록이었고, 스텝
     * off-by-one({@code chunk + 1})은 더 조용하다 — 청크마다 한 명씩 흘리므로 n=501 에서
     * 500 이 되는데, 청크를 한 번도 넘지 않는 벡터로는 정상과 "조용한 부분 복원" 을 <b>구분할 수
     * 없다</b>. 이 티켓이 가장 무서워하는 실패 모양이 바로 그 조용한 부분 복원이다.
     *
     * <p>{@code SADD_CHUNK} 를 참조한다 — 수를 베껴 적으면 상수를 줄일 때 이 벡터가 다시
     * 경계 아래로 내려간다.
     */
    @Test
    @DisplayName("⭐ 청크 경계를 넘는 명단도 전량 복원된다 — 한 명도 흘리지 않는다")
    void reconcileRestoresAcrossChunkBoundaries() {
        // given: 청크 하나를 넘긴다
        int count = ChatKickWriteRedisRepository.SADD_CHUNK + 1;
        java.util.List<UUID> many = java.util.stream.Stream.generate(UUID::randomUUID)
                .limit(count).toList();

        // when
        repository.reconcile(roomId, many);

        // then
        assertThat(redisTemplate.opsForSet().size(key()))
                .as("청크마다 한 명씩 흘리는 off-by-one 이 여기서만 드러난다")
                .isEqualTo((long) count);
        assertThat(redisTemplate.opsForSet().members(key()))
                .containsExactlyInAnyOrderElementsOf(many.stream().map(UUID::toString).toList());
    }

    @Test
    @DisplayName("재적재는 멱등이다 — 같은 정본으로 두 번 불러도 결과가 같다")
    void reconcileIsIdempotent() {
        // given
        UUID other = UUID.randomUUID();
        repository.reconcile(roomId, java.util.List.of(userId, other));

        // when
        repository.reconcile(roomId, java.util.List.of(userId, other));

        // then
        assertThat(redisTemplate.opsForSet().members(key()))
                .containsExactlyInAnyOrder(userId.toString(), other.toString());
        assertThat(redisTemplate.getExpire(key())).isEqualTo(-1L);
    }

    @Test
    @DisplayName("회수용 만료가 붙어 있던 키도 재적재로 만료가 걷힌다")
    void reconcileClearsARecoveryExpiry() {
        // given: 방 종료 신호로 만료가 붙은 상태
        repository.register(roomId, userId);
        redisTemplate.expire(key(), Duration.ofHours(24));
        assertThat(redisTemplate.getExpire(key())).isPositive();

        // when
        repository.reconcile(roomId, java.util.List.of(userId));

        // then: 만료가 남으면 되살린 명단이 그 시각에 통째로 사라진다
        assertThat(redisTemplate.getExpire(key())).isEqualTo(-1L);
    }
}
