package com.sapari.chat.infrastructure.persistence.repository;

import org.springframework.dao.QueryTimeoutException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.sapari.chat.infrastructure.persistence.entity.ChatBanEntity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.support.LiveSchema;
import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.repository.ChatBanStateRepository.BanWrite;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatRole;

/**
 * 밴 정본 조회가 <b>SQL이 만드는 보장</b>에 기대는 부분을 실제 Postgres에서 고정한다.
 *
 * <p>목으로는 검증되는 것이 없는 자리가 셋이다. 사용자당 한 행을 세우는 <b>유니크 제약</b>,
 * 만료를 늘리는 방향으로만 반영하는 <b>upsert 가드</b>, 그리고 누적 강퇴를 세는 <b>창의 경계</b>다.
 * 셋 중 어느 것이 틀려도 밴이 조용히 일찍 풀리거나 아예 걸리지 않는다.
 *
 * <p>{@code expires_at IS NULL}을 "가장 먼 만료"로 다루는 것은 이제 정렬이 아니라 upsert 가드가 지킨다 —
 * 조회 쪽 정렬은 제약이 서면서 결과를 가르지 못하게 되어 뺐다.
 *
 * <p>스키마는 운영과 같은 Flyway 파일을 그대로 실행해 만든다. 테스트에 DDL을 다시 적으면 두 벌이 되고,
 * 어긋나는 순간 초록인 채로 어긋난다. {@code ddl-auto=validate}가 엔티티 매핑까지 대조한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("ChatBanStateRepository — 사용자당 한 행, 늘리기만 한다")
class ChatBanStateRepositoryImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void applyRealSchema() throws Exception {
        LiveSchema.applyTo(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }


    @Autowired
    private ChatBanJpaRepository banJpaRepository;
    @Autowired
    private ChatKickLogJpaRepository kickLogJpaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 슬라이스는 Spring Data 리포지토리만 올리고 어댑터는 스캔하지 않아 직접 엮는다. */
    private ChatBanStateRepositoryImpl repository() {
        return new ChatBanStateRepositoryImpl(banJpaRepository);
    }

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final UUID userId = UUID.randomUUID();

    @Test
    @DisplayName("만료가 지난 밴은 활성이 아니다")
    void expiredBanIsNotActive() {
        // given
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.minus(Duration.ofDays(1)),
                NOW.minus(Duration.ofDays(8))));

        // when & then
        assertThat(repository().findActive(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("만료가 남은 밴은 활성이다")
    void unexpiredBanIsActive() {
        // given
        Instant expiry = NOW.plus(Duration.ofDays(3));
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, expiry, NOW.minus(Duration.ofDays(4))));

        // when
        ChatBan found = repository().findActive(userId, NOW).orElseThrow();

        // then
        assertThat(found.expiresAt()).isEqualTo(expiry);
        assertThat(found.bannedById()).isEqualTo(SYSTEM);
    }

    @Test
    @DisplayName("만료가 NULL인 영구 밴도 활성이다 — 비교 연산으로만 거르면 통째로 사라진다")
    void permanentBanIsActive() {
        // given
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, null, NOW.minus(Duration.ofDays(1))));

        // when
        ChatBan found = repository().findActive(userId, NOW).orElseThrow();

        // then
        assertThat(found.isPermanent()).isTrue();
    }

    @Test
    @DisplayName("⭐ 짧은 밴 뒤에 긴 밴이 오면 늘어난다 — 덮어쓰면 미러가 정본보다 일찍 풀린다")
    void shorterBanIsExtendedByALongerOne() {
        // given: 짧은 밴이 자리를 잡은 뒤 더 긴 밴이 온다
        Instant shortExpiry = NOW.plus(Duration.ofDays(3));
        Instant longExpiry = NOW.plus(Duration.ofDays(30));
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, shortExpiry, NOW.minus(Duration.ofDays(4))));
        repository().extendOrCreate(new ChatBan(userId, UUID.randomUUID(), longExpiry, NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW).orElseThrow().expiresAt()).isEqualTo(longExpiry);
    }

    @Test
    @DisplayName("⭐ 기한부 밴은 영구로 승격된다 — 만료 없음이 가장 긴 만료다")
    void datedBanIsPromotedToPermanent() {
        // given
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(365)),
                NOW.minus(Duration.ofDays(1))));
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, null, NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW).orElseThrow().isPermanent()).isTrue();
    }

    @Test
    @DisplayName("⭐ 긴 밴 뒤에 짧은 밴이 와도 줄어들지 않는다 — 동시 강퇴에서 늦게 도착하는 쪽이 짧을 수 있다")
    void shorterBanDoesNotShortenAnExistingOne() {
        // given: 한 달짜리가 먼저 자리를 잡았다
        Instant month = NOW.plus(Duration.ofDays(30));
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, month, NOW));

        // when: 일주일짜리가 뒤에 도착한다
        BanWrite write = repository().extendOrCreate(
                new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(7)), NOW));

        // then: 바꾼 것이 없다고 알리고, 실제로 남아 있는 밴(한 달)을 돌려준다.
        // 이 호출이 건 짧은 값을 돌려주면 호출자가 정본에 없는 만료를 미러에 싣는다.
        assertThat(write.applied()).isFalse();
        assertThat(write.effective().expiresAt()).isEqualTo(month);

        // then: 한 달이 그대로 남는다. 줄어들면 그 사람은 23일 일찍 돌아온다
        assertThat(repository().findActive(userId, NOW))
                .get()
                .extracting(ChatBan::expiresAt)
                .isEqualTo(month);
    }

    @Test
    @DisplayName("⭐ 영구 밴은 어떤 기한부 밴도 덮지 못한다")
    void permanentBanIsNeverShortened() {
        // given
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, null, NOW));

        // when
        BanWrite write = repository().extendOrCreate(
                new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(30)), NOW));

        // then: 영구를 이길 수 없으므로 바꾼 것이 없고, 돌려주는 것도 영구다
        assertThat(write.applied()).isFalse();
        assertThat(write.effective().expiresAt()).isNull();
        assertThat(repository().findActive(userId, NOW))
                .get()
                .extracting(ChatBan::expiresAt)
                .isNull();
    }

    /**
     * ⭐ <b>T-12의 본체.</b> 서로 다른 방에서 같은 사람을 동시에 강퇴하면 두 호출이 각각 "활성 밴 없음"을
     * 읽고 각각 쓴다 — READ COMMITTED라 서로의 미커밋 행이 보이지 않아, 트랜잭션을 열어도 막히지 않는다.
     * 막는 것은 격리 수준이 아니라 유니크 제약이다.
     *
     * <p>행이 둘이 되면 아픈 자리는 <b>해제</b>다. 해제는 행 DELETE인데 행이 몇 개인지 모르면 하나를
     * 지워도 다른 행이 남아 그 사람은 계속 막힌다 — 화면에는 "해제됨"이라고 뜨는 채로.
     *
     * <p>이 스위트의 다른 테스트와 달리 <b>경계를 직접 연다</b>. 슬라이스가 감싸 주는 트랜잭션은 이
     * 스레드 것이라 다른 스레드에서 보이지 않고, 그러면 동시성이 재현되지 않는다.
     */
    @Test
    @DisplayName("⭐ 동시에 밴을 걸어도 행은 하나다 — 둘이 되면 해제가 반쪽이 된다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBansCollapseIntoOneRow() throws Exception {
        // given: 만료가 제각각인 여덟 개가 같은 순간에 도착한다
        int writers = 8;
        Instant longest = NOW.plus(Duration.ofDays(365));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                // 긴 것은 하나뿐이다. 절반씩 두면 가드가 없어도 마지막 커밋이 이길 확률이 절반이라
                // 단조 규칙 회귀를 이 테스트가 우연에 맡기게 된다.
                Instant expiry = (i == 0) ? longest : NOW.plus(Duration.ofHours(12));
                futures.add(pool.submit(() -> {
                    start.await();
                    // 경계를 스레드마다 따로 연다 — @Modifying 네이티브 쿼리는 경계 없이는 실행되지 않는다
                    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                            repository().extendOrCreate(new ChatBan(userId, SYSTEM, expiry, NOW)));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }

            // then: 살아남은 하나가 가장 긴 것인지 본다. 행 수 자체는 여기서 관측되지 않는다 —
            // 제약이 없으면 upsert의 ON CONFLICT가 먼저 죽어 이 단언에 닿지 못한다.
            // "사용자당 한 행"을 결정적으로 재는 것은 평문 INSERT 테스트 쪽이다.
            assertThat(banJpaRepository.findActive(userId, NOW))
                    .as("동시 강퇴 뒤 살아 있는 밴이 없다")
                    .get()
                    .extracting(ChatBanEntity::getExpiresAt)
                    .as("살아남은 행이 가장 긴 밴이 아니다 — 짧은 쪽이 이기면 정본이 미러보다 먼저 풀린다")
                    .isEqualTo(longest);
        } finally {
            pool.shutdownNow();
            cleanUp();
        }
    }

    /**
     * ⭐ <b>T-12가 기대는 제약 자체를 잰다.</b>
     *
     * <p>다른 테스트들은 전부 {@code upsertExtending}을 지나는데, 그 쿼리의 {@code ON CONFLICT}는
     * 유니크 인덱스가 없으면 <b>실행 자체가 안 된다</b>. 그래서 인덱스를 지우면 셋업이 죽을 뿐 어떤
     * 단언도 "행이 둘이 됐다"를 말하지 못한다 — 처음엔 그걸 두고 "잴 수 없다"고 결론지었는데 틀렸다.
     *
     * <p>평문 INSERT는 {@code ON CONFLICT}를 쓰지 않아 그 결합에서 자유롭다. 인덱스가 있으면 두 번째가
     * 거부되고, 없으면 그냥 들어간다 — 그 차이가 <b>이 테스트의 then에서</b> 갈린다.
     *
     * <p>리포지토리를 지나지 않고 JDBC로 쓰는 이유가 그것이다. 여기서 재는 것은 우리 쿼리가 아니라
     * 스키마다.
     */
    @Test
    @DisplayName("⭐ 사용자당 밴 행은 하나 — 평문 INSERT 두 번째가 제약에 막힌다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondRowForSameUserIsRejectedByTheConstraint() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            // given: 첫 행은 들어간다
            insertPlain(connection, NOW.plus(Duration.ofDays(7)));

            // when & then: 같은 사용자의 두 번째 행은 제약이 막는다.
            // 막지 못하면 해제(행 DELETE)가 하나를 지워도 남은 행이 계속 그 사람을 막는다.
            assertThatThrownBy(() -> insertPlain(connection, NOW.plus(Duration.ofDays(30))))
                    .as("같은 사용자에 밴 행이 둘 들어갔다 — 해제가 반쪽이 된다")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("uk_chat_ban_user_id");
        } finally {
            cleanUp();
        }
    }

    /**
     * ⭐ <b>상한 초과가 실제로 어떤 타입으로 오는지</b>를 실 Postgres 경합으로 못 박는다.
     *
     * <p>번역({@code KickUserService})을 지키는 것이 목이 던지는 예외뿐이면, 그 목은 <b>우리가 믿는 타입</b>을
     * 던진다. Spring이나 드라이버가 올라가며 실제 타입이 바뀌면 스위트는 초록인 채로 절반이 다시 500이
     * 된다 — 리뷰어가 실측으로 확인해 줬지만 그 확인은 커밋에 남지 않는다.
     *
     * <p>여기서 재는 것은 잠금에 걸린 문이 취소되는 갈래다. 데드라인 갈래
     * ({@code TransactionTimedOutException})는 문과 문 사이에서 나므로 이 하네스로는 만들 수 없고,
     * {@code KickUserServiceTest}가 두 타입을 모두 번역하는지 따로 지킨다.
     */
    @Test
    @DisplayName("⭐ 잠금에 걸려 상한을 넘으면 QueryTimeoutException이다 — 번역이 잡는 타입이 실제 타입인가")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockContentionSurfacesAsQueryTimeout() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // given: 다른 세션이 이 사용자의 밴 행을 쥔 채 커밋하지 않는다
        Thread holder = new Thread(() -> {
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                connection.setAutoCommit(false);
                insertPlain(connection, NOW.plus(Duration.ofDays(7)));
                holding.countDown();
                release.await(30, TimeUnit.SECONDS);
                connection.rollback();
            } catch (Exception e) {
                holding.countDown();
            }
        });
        holder.start();
        holding.await(30, TimeUnit.SECONDS);
        try {
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setTimeout(1);

            // when & then: 상한을 넘긴 쓰기가 어떤 타입으로 나오는가
            assertThatThrownBy(() -> template.executeWithoutResult(status ->
                    repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(30)), NOW))))
                    .as("번역이 잡는 타입과 실제로 나오는 타입이 갈렸다 — 갈리면 절반이 500으로 나간다")
                    .isInstanceOf(QueryTimeoutException.class);
        } finally {
            release.countDown();
            holder.join(30_000);
            cleanUp();
        }
    }

    private void insertPlain(Connection connection, Instant expiresAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO live_schema.chat_ban (user_id, banned_by_id, expires_at, created_at)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setObject(1, userId);
            statement.setObject(2, SYSTEM);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.setTimestamp(4, Timestamp.from(NOW));
            statement.executeUpdate();
        }
    }

    /**
     * NOT_SUPPORTED라 롤백이 없다 — 커밋된 행을 직접 치운다.
     *
     * <p><b>우리 쿼리를 지나지 않는다.</b> {@code findActive}는 결과가 하나라는 전제 위에 있어서, 제약이
     * 없는 상태(= 이 스위트가 되돌림으로 만들어 내는 바로 그 상태)에서는 {@code IncorrectResultSizeDataAccessException(cause: NonUniqueResultException)}
     * 으로 죽는다. 정리가 {@code finally}에서 터지면 <b>그것이 단언 실패를 덮어써서</b>, 테스트는 빨간불인데
     * 이유가 "제약이 막지 않았다"가 아니라 "정리가 실패했다"가 된다. 실제로 그렇게 나왔다.
     *
     * <p>그래서 행 수와 무관한 평문 DELETE를 쓴다.
     */
    private void cleanUp() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM live_schema.chat_ban WHERE user_id = ?")) {
            statement.setObject(1, userId);
            statement.executeUpdate();
        }
    }

    @Test
    @DisplayName("남의 밴은 보이지 않는다")
    void otherUsersBanIsInvisible() {
        // given
        repository().extendOrCreate(new ChatBan(UUID.randomUUID(), SYSTEM, NOW.plus(Duration.ofDays(3)),
                NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("⭐ 누적 강퇴는 방을 가리지 않고, 창 밖의 것은 세지 않는다")
    void countsAcrossRoomsWithinTheWindow() {
        // given: 창 안 두 건(서로 다른 방) + 창 밖 한 건
        ChatKickLogRepositoryImpl kickLogs = new ChatKickLogRepositoryImpl(kickLogJpaRepository);
        Instant since = NOW.minus(Duration.ofDays(730));
        kickLogs.appendIfAbsent(kick(NOW.minus(Duration.ofDays(1))));
        kickLogs.appendIfAbsent(kick(NOW.minus(Duration.ofDays(700))));
        kickLogs.appendIfAbsent(kick(since.minus(Duration.ofDays(1))));

        // when & then: 방이 셋으로 갈려 있어도 사람 기준으로 합산된다
        assertThat(kickLogs.countDistinctKickersSince(userId, since)).isEqualTo(2);
    }

    private ChatKickLog kick(Instant kickedAt) {
        return new ChatKickLog(userId, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "문제된 원문", kickedAt);
    }
}
