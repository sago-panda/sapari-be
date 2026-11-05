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
import org.springframework.transaction.TransactionDefinition;
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

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
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

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
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("시작 래치가 30초 안에 열리지 않았다 — 주 스레드가 countDown 전에 터졌다. 상한이 없으면 이 스레드가 영원히 블록된 채 남아 같은 JVM 의 뒤따르는 테스트를 오염시킨다");
                    }
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
        assertThat(kickLogs.countConfirmingKickersSince(userId, since)).isEqualTo(2);
    }

    @Test
    @DisplayName("⭐ 해제는 행을 지우고 사면 기준선을 남긴다 — 활성이든 만료든")
    void releaseRemovesTheRowAndLeavesABaseline() {
        // given: 만료가 남은 밴
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(30)),
                NOW.minus(Duration.ofDays(1))));
        assertThat(repository().findActive(userId, NOW)).isPresent();

        // when
        var removed = repository().release(userId, ADMIN, NOW).banExisted();

        // then
        assertThat(removed).isTrue();
        assertThat(repository().findActive(userId, NOW))
                .as("정본에 행이 남으면 다음 자동 승격의 ON CONFLICT 대상이 되어 발급자·생성시각이 덮인다")
                .isEmpty();
        assertThat(latestReleaseOf(userId))
                .as("기준선이 없으면 새 방 강퇴 1회로 같은 티어가 재적용된다")
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName("⭐ 풀 밴이 없어도 해제는 성공하고 기준선을 남긴다 — 멱등")
    void releaseIsIdempotentAndStillFoldsTheTally() {
        // when: 밴이 애초에 없다
        var removed = repository().release(userId, ADMIN, NOW).banExisted();

        // then
        assertThat(removed)
                .as("지운 행이 없다는 사실은 돌려주지만 실패가 아니다 — 재시도가 미러 삭제에 닿아야 한다")
                .isFalse();
        assertThat(latestReleaseOf(userId))
                .as("만료가 이미 지난 사람에게 누르는 것도 '누적을 접는다'는 뜻이다")
                .isEqualTo(NOW);
    }

    /**
     * 이 티켓의 핵심 벡터다 — 기준선이 없으면 해제가 <b>해제가 아니라 유예</b>가 된다.
     *
     * <p>해제는 {@code chat_ban} 행만 지우므로 {@code chat_kick_log} 가 그대로 남는다. 창이 730일이라
     * 과거 강퇴자가 계속 세어지고, 그러면 해제된 사용자가 강퇴 한 번을 더 받는 순간 같은 티어가
     * 재적용된다. 기준선이 그 재적용을 막는다.
     *
     * <p><b>벡터는 반드시 새 방이어야 한다.</b> {@code ChatKickRecorder} 가
     * {@code firstKickInThisRoom} 이 아니면 세는 걸음에 오지 않으므로, 같은 방으로 재면 재밴이
     * 안 일어나고 "막혔다" 는 거짓 통과가 나온다. 여기서는 {@code kickBy} 가 방을 매번 새로 만든다.
     */
    @Test
    @DisplayName("⭐ 해제 뒤에는 과거 강퇴자가 세어지지 않는다 — 새 방 강퇴 1회로 재밴되지 않는다")
    void releaseFoldsThePastTally() {
        // given: 서로 다른 판매자 3명에게 강퇴당해 ONE_WEEK 임계(3)에 닿은 상태
        ChatKickLogRepositoryImpl kickLogs = new ChatKickLogRepositoryImpl(kickLogJpaRepository);
        Instant since = NOW.minus(Duration.ofDays(730));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(3))));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(2))));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(1))));
        assertThat(kickLogs.countConfirmingKickersSince(userId, since))
                .as("전제가 성립해야 이 테스트가 의미를 갖는다 — 임계 3 에 닿아 있어야 한다")
                .isEqualTo(3);

        // when: 해제하고, 그 뒤에 <새 방>에서 한 번 더 강퇴당한다
        repository().release(userId, ADMIN, NOW);
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.plus(Duration.ofMinutes(1))));

        // then: 해제 이후의 강퇴 하나만 세어진다
        assertThat(kickLogs.countConfirmingKickersSince(userId, since))
                .as("과거 강퇴자가 다시 세어지면 강퇴 1회로 같은 티어가 재적용된다 — 해제가 유예가 된다")
                .isEqualTo(1);
    }

    /**
     * ⭐ 사면 기준선의 <b>사용자 스코프</b>를 지킨다.
     *
     * <p>이 테스트가 없으면 상관 서브쿼리의 {@code WHERE user_id = :userId} 를 떼도 <b>전 스위트가
     * 초록</b>이다 — 이 클래스의 다른 테스트가 대상 사용자를 하나만 쓰기 때문이다({@code kickBy} 의
     * {@code randomUUID} 는 전부 강퇴자·방 쪽이고 대상은 늘 같은 {@code userId}). 한 사용자만 있는
     * 데이터에서는 스코프가 있으나 없으나 결과가 같다.
     *
     * <p>스코프가 사라지면 <b>한 사람의 해제 1건이 플랫폼 전원의 누적을 접는다.</b> 증상이
     * "아무도 밴이 안 걸린다" 라서 사고 중에 원인이 이 한 줄로 수렴하지 않는다.
     */
    @Test
    @DisplayName("⭐ 사면 기준선은 그 사용자에게만 적용된다 — 스코프를 떼면 전원의 누적이 접힌다")
    void theAmnestyBaselineIsScopedToItsUser() {
        // given: 서로 다른 두 사용자가 각각 강퇴 이력을 갖는다
        ChatKickLogRepositoryImpl kickLogs = new ChatKickLogRepositoryImpl(kickLogJpaRepository);
        Instant since = NOW.minus(Duration.ofDays(730));
        UUID otherUserId = UUID.randomUUID();
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(2))));
        kickLogs.appendIfAbsent(new ChatKickLog(otherUserId, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "문제된 원문", NOW.minus(Duration.ofDays(2))));
        assertThat(kickLogs.countConfirmingKickersSince(otherUserId, since)).isEqualTo(1);

        // when: 한 사람만 해제한다
        repository().release(userId, ADMIN, NOW);

        // then
        assertThat(kickLogs.countConfirmingKickersSince(userId, since))
                .as("해제한 사용자의 누적은 접힌다")
                .isZero();
        assertThat(kickLogs.countConfirmingKickersSince(otherUserId, since))
                .as("남의 누적이 같이 접히면 한 사람의 해제가 플랫폼 전원을 사면한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("⭐ 해제는 지운 밴의 스냅샷을 돌려준다 — 정본이 사라지면 그 내용이 어디에도 없다")
    void releaseReturnsASnapshotOfWhatItRemoved() {
        // given: 사람이 넣은 영구 밴(만료 없음). 수동 밴 경로가 0건이라 다시 걸 수 없는 값이다
        UUID admin = UUID.randomUUID();
        repository().extendOrCreate(new ChatBan(userId, admin, null, NOW.minus(Duration.ofDays(1))));

        // when
        var released = repository().release(userId, ADMIN, NOW);

        // then
        assertThat(released.banExisted()).isTrue();
        assertThat(released.expiresAt())
                .as("영구였다는 사실이 사라지면 무엇을 되돌려야 하는지 모른다")
                .isNull();
        assertThat(released.bannedById())
                .as("발급자가 사라지면 자동 승격이었는지 사람이 걸었는지 모른다")
                .isEqualTo(admin);
    }

    @Test
    @DisplayName("⭐ 풀 밴이 없었다는 사실이 스냅샷에 남는다 — 활성 밴을 푼 것과 같은 행이면 안 된다")
    void releaseRecordsThatThereWasNoBan() {
        // when
        var released = repository().release(userId, ADMIN, NOW);

        // then: 오타 한 글자로 남의 누적을 접었을 때 그것이 기록에서 구분돼야 한다
        assertThat(released.banExisted()).isFalse();
        assertThat(released.expiresAt()).isNull();
        assertThat(released.bannedById()).isNull();
    }

    /**
     * ⭐ 해제의 두 쓰기가 <b>한 트랜잭션</b>인지, 슬라이스의 도움을 걷어내고 잰다.
     *
     * <p>{@code modules/chat/AGENTS.md} 가 처방을 명시한다 — "Unit tests cannot see this …
     * {@code @DataJpaTest} opens a transaction for you. {@code ChatKickRecorderTest} calls with
     * {@code Propagation.NOT_SUPPORTED} to remove that help." 이 클래스가 그 처방을 이미 세 번
     * 쓰는데 해제 테스트에는 없었다. 그래서 {@code ReleaseBanService} 가 선언한 불변식("반환됐다는
     * 것은 커밋이 확정됐다는 뜻")과 "DELETE + INSERT 가 같이 롤백된다" 가 어느 테스트에도 안 걸렸다 —
     * {@code ChatModerationWiringTest} 는 프록시 <b>존재</b>만 본다.
     *
     * <p>여기서는 경계를 걷어내고 <b>포트를 직접</b> 부른다. 포트가 경계를 만들지 않는다는 계약이
     * 참이면 {@code @Modifying} 네이티브 쿼리가 시끄럽게 죽어야 한다 — 조용히 통과하면 운영에서
     * 인가를 지난 뒤 500 이 되고, 그게 이 처방이 생긴 두 사고 중 하나다.
     */
    @Test
    @DisplayName("⭐ 경계 없이 해제를 부르면 시끄럽게 죽는다 — 조용히 통과하면 운영에서 500 이다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void releaseWithoutATransactionFailsLoudly() {
        // when & then: 두 쓰기가 모두 @Modifying 네이티브라 경계가 없으면 거부된다
        assertThatThrownBy(() -> repository().release(userId, ADMIN, NOW))
                .as("경계 없이 통과하면 '한 트랜잭션' 이라는 계약이 아무것도 아니게 된다")
                .isInstanceOf(org.springframework.dao.InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("No active transaction");
    }

    /**
     * ⭐ 두 쓰기가 <b>같이</b> 롤백되는지 — 갈라지면 둘 중 하나의 잔여 상태가 남는다.
     *
     * <p>기준선만 남고 밴이 남으면 다음 강퇴에서 티어가 내려가 보여 원인 추적이 어렵고, 밴만
     * 지워지고 기준선이 없으면 해제가 새 방 강퇴 1회까지의 유예가 된다. 한 트랜잭션이면 둘 다
     * 안 생기는데, 그 사실을 재려면 <b>중간에 실패시켜</b> 봐야 한다.
     */
    @Test
    @DisplayName("⭐ 기준선 INSERT 가 실패하면 밴 삭제도 함께 되감긴다")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bothWritesRollBackTogether() {
        // given: 밴이 있고, 기준선 INSERT 를 실패시킬 상태를 만든다 — released_at NOT NULL 위반
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> repository().extendOrCreate(
                new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(30)), NOW.minus(Duration.ofDays(1)))));

        // when: now 를 null 로 넘겨 INSERT 만 깨뜨린다(삭제는 이미 돌았다)
        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> repository().release(userId, ADMIN, null)))
                .isInstanceOf(RuntimeException.class);

        // then: 밴이 그대로 있어야 한다 — 삭제만 커밋됐다면 "기준선 없는 해제" 가 남는다
        TransactionTemplate read = new TransactionTemplate(transactionManager);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        java.util.Optional<ChatBan> survivor =
                read.execute(status -> repository().findActive(userId, NOW));
        assertThat(survivor)
                .as("기준선이 실패했는데 밴만 지워지면 해제가 '다음 강퇴 1회까지의 유예' 가 된다")
                .isPresent();
    }

    @Test
    @DisplayName("해제가 없으면 종전과 똑같이 센다 — 기준선은 창을 좁히기만 한다")
    void withoutAReleaseTheWindowIsUnchanged() {
        // given
        ChatKickLogRepositoryImpl kickLogs = new ChatKickLogRepositoryImpl(kickLogJpaRepository);
        Instant since = NOW.minus(Duration.ofDays(730));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(2))));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(1))));

        // when & then: COALESCE 기본값이 :since 라 해제 이력이 없으면 동작이 같다
        assertThat(kickLogs.countConfirmingKickersSince(userId, since)).isEqualTo(2);
    }

    @Test
    @DisplayName("기준선은 가장 최근 해제가 이긴다 — 두 번 풀면 뒤의 것이 창을 정한다")
    void theLatestReleaseWins() {
        // given
        ChatKickLogRepositoryImpl kickLogs = new ChatKickLogRepositoryImpl(kickLogJpaRepository);
        Instant since = NOW.minus(Duration.ofDays(730));
        repository().release(userId, ADMIN, NOW.minus(Duration.ofDays(10)));
        kickLogs.appendIfAbsent(kickBy(UUID.randomUUID(), NOW.minus(Duration.ofDays(5))));

        // when: 다시 풀면 그 사이의 강퇴도 접힌다
        repository().release(userId, ADMIN, NOW);

        // then
        assertThat(kickLogs.countConfirmingKickersSince(userId, since)).isZero();
    }

    /**
     * {@code chat_ban_release} 의 가장 최근 기준선.
     *
     * <p><b>같은 영속 컨텍스트로 읽어야 한다.</b> 처음에 별 JDBC 커넥션을 열었더니 {@code null} 이
     * 나왔다 — {@code @DataJpaTest} 가 테스트를 롤백 트랜잭션에 담으므로 그 안의 INSERT 는 다른
     * 커넥션에 보이지 않는다. "기준선이 안 남았다" 와 "다른 커넥션이라 안 보인다" 가 같은 출력이다.
     */
    private Instant latestReleaseOf(UUID user) {
        Object at = entityManager.createNativeQuery(
                        "SELECT MAX(released_at) FROM live_schema.chat_ban_release WHERE user_id = :u")
                .setParameter("u", user)
                .getSingleResult();
        // 드라이버가 timestamptz 를 Instant 로 돌려준다 — Timestamp 로 캐스트하면 ClassCastException 이다.
        return at == null ? null : ((Instant) at);
    }

    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-0000000000ad");

    private ChatKickLog kick(Instant kickedAt) {
        return new ChatKickLog(userId, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "문제된 원문", kickedAt);
    }

    /** 방을 매번 새로 만든다 — 같은 방이면 유니크 제약에 걸려 세는 걸음에 도달하지 않는다. */
    private ChatKickLog kickBy(UUID kickerId, Instant kickedAt) {
        return new ChatKickLog(userId, UUID.randomUUID(), kickerId,
                ChatRole.SELLER, "문제된 원문", kickedAt);
    }
}
