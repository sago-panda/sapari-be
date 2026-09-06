package com.sapari.chat.infrastructure.persistence.repository;

import org.springframework.data.domain.Limit;
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
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatRole;

/**
 * 밴 정본 조회가 <b>SQL이 만드는 보장</b>에 기대는 부분을 실제 Postgres에서 고정한다.
 *
 * <p>목으로는 검증되는 것이 없는 자리가 둘이다. 하나는 {@code expires_at IS NULL}을 "만료 없음"이 아니라
 * "가장 먼 만료"로 취급하는 정렬({@code NULLS FIRST})이고, 다른 하나는 누적 강퇴를 세는 창의 경계다.
 * 둘 다 틀리면 밴이 조용히 일찍 풀리거나 아예 걸리지 않는다.
 *
 * <p>스키마는 운영과 같은 Flyway 파일을 그대로 실행해 만든다. 테스트에 DDL을 다시 적으면 두 벌이 되고,
 * 어긋나는 순간 초록인 채로 어긋난다. {@code ddl-auto=validate}가 엔티티 매핑까지 대조한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("ChatBanStateRepository — 가장 오래 가는 밴을 고른다")
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
    void picksTheLongestLivingBan() {
        // given: 자동 밴 위에 관리자가 더 긴 밴을 얹은 모양
        Instant shortExpiry = NOW.plus(Duration.ofDays(3));
        Instant longExpiry = NOW.plus(Duration.ofDays(30));
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, shortExpiry, NOW.minus(Duration.ofDays(4))));
        repository().extendOrCreate(new ChatBan(userId, UUID.randomUUID(), longExpiry, NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW).orElseThrow().expiresAt()).isEqualTo(longExpiry);
    }

    @Test
    @DisplayName("⭐ 기한부 밴은 영구로 승격된다 — 만료 없음이 가장 긴 만료다")
    void permanentOutranksAnyDatedBan() {
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
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(7)), NOW));

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
        repository().extendOrCreate(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(30)), NOW));

        // then
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
                Instant expiry = (i % 2 == 0) ? longest : NOW.plus(Duration.ofHours(12));
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

            // then: 제약이 없으면 여기서 여덟 행이 남는다
            assertThat(banJpaRepository.findActive(userId, NOW, Limit.of(writers)))
                    .as("동시 강퇴가 밴 행을 여러 개 남겼다 — 해제가 행 하나를 지워도 나머지가 계속 막는다")
                    .hasSize(1)
                    .first()
                    .extracting(ChatBanEntity::getExpiresAt)
                    .as("살아남은 행이 가장 긴 밴이 아니다 — 짧은 쪽이 이기면 정본이 미러보다 먼저 풀린다")
                    .isEqualTo(longest);
        } finally {
            pool.shutdownNow();
            // NOT_SUPPORTED라 롤백이 없다 — 커밋된 행을 직접 치운다
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    banJpaRepository.deleteAll(banJpaRepository.findActive(userId, NOW, Limit.of(writers))));
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
        assertThat(kickLogs.countSince(userId, since)).isEqualTo(2);
    }

    private ChatKickLog kick(Instant kickedAt) {
        return new ChatKickLog(userId, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "문제된 원문", kickedAt);
    }
}
