package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanStateRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogRepositoryImpl;
import com.sapari.chat.support.LiveSchema;

/**
 * 같은 사람을 <b>서로 다른 방에서 동시에</b> 강퇴할 때 확증 임계가 지켜지는지 실제 Postgres에서 고정한다.
 *
 * <p>왜 별도 스위트인가: 레이스를 결정적으로 만들려면 "둘 다 기록한 뒤에 둘 다 센다"를 강제해야 하고,
 * 그 창을 열려면 포트에 래치를 끼워야 한다. 그 배선을 기존 스위트에 넣으면 그쪽 테스트 전부가 쓰지도
 * 않는 데코레이터를 지나간다.
 *
 * <p><b>왜 Postgres가 필요한가.</b> 결함이 격리 수준에서 나온다 — 기본 READ COMMITTED에서는 남의
 * 미커밋 행이 보이지 않으므로 두 트랜잭션이 각자 "기존 + 나" 만 센다. 목으로는 이 성질이 재현되지 않고,
 * {@code @DataJpaTest}가 열어 주는 공용 트랜잭션 하나로도 재현되지 않는다(두 경로가 같은 트랜잭션이 된다).
 * 그래서 {@code NOT_SUPPORTED}로 그 도움을 걷어내고 스레드마다 제 트랜잭션을 쓰게 한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ChatKickRecorderConcurrencyTest.Wiring.class)
@DisplayName("ChatKickRecorder — 동시 강퇴에서도 확증 임계가 지켜진다")
class ChatKickRecorderConcurrencyTest {

    /**
     * 기록과 집계 사이에 창을 여는 데코레이터.
     *
     * <p><b>이 장치는 깨진 코드에서만 랑데부를 만든다.</b> 직렬화가 없으면 두 트랜잭션이 각자 기록한 뒤
     * 서로를 만나 둘 다 집계에 들어가고, 그러면 각자 "기존 + 나"만 세어 결함이 매번 재현된다. 반대로
     * 직렬화가 있으면 한쪽은 기록에 닿기도 전에 잠금에서 멈추므로 <b>랑데부는 반드시 실패해야 한다</b> —
     * 초록불 경로에서 이 기다림은 매번 상한에 걸리고, 이 장치는 그때 상한만큼의 지연기로만 동작한다.
     * 회귀 가드로서는 그게 맞는 성질이다.
     *
     * <p>그래서 <b>기다림에 상한이 있는 것이 핵심이다.</b> 상한이 없으면 고친 코드가 여기서 교착한다 —
     * 이 테스트가 수정 후에 통과하려면 상한이 있어야 한다. 상한이 지나면 그냥 진행하고, 그때는 앞선
     * 쪽이 커밋하며 잠금을 놓아 뒤쪽이 온전한 수를 센다.
     *
     * <p>{@code await}의 결과는 {@link LatchedKickLogRepository#rendezvousWaitedInVain()}으로 노출된다 —
     * 그 값이 "둘이 만났다"(= 직렬화 안 됨)와 "한쪽이 헛되게 기다렸다"(= 직렬화됨)를 가르므로, 잠금
     * 키가 사용자 단위인지를 재는 축이 거기 걸려 있다. 서로 다른 대상 둘을 동시에 강퇴하면 만나야
     * 하고, 전역 단일 키로 바뀌면 못 만난다 — 그 축은 아래 "잠금은 사용자 단위다"가 재고, 상수 키로
     * 바꾸면 깨진다.
     */
    static class LatchedKickLogRepository implements ChatKickLogRepository {

        private final ChatKickLogRepository delegate;
        private volatile CountDownLatch recorded;
        /** 상한까지 기다렸는데 상대가 오지 않은 횟수 — 이 값이 "직렬화됐는가"를 가른다. */
        private final java.util.concurrent.atomic.AtomicInteger waitedInVain =
                new java.util.concurrent.atomic.AtomicInteger();

        LatchedKickLogRepository(ChatKickLogRepository delegate) {
            this.delegate = delegate;
        }

        void arm(CountDownLatch latch) {
            this.waitedInVain.set(0);
            this.recorded = latch;
        }

        void disarm() {
            this.recorded = null;
        }

        /**
         * 상한까지 기다렸는데 상대가 오지 않은 횟수 — <b>직렬화 여부의 직접 증거다.</b>
         *
         * <p>헛되게 기다린 쪽이 있었다면 그때 상대는 기록에 닿기도 전에 잠금에서 멈춰 있었다는 뜻이다.
         * 반대로 아무도 헛되게 기다리지 않았다면 둘 다 기록에 도달했다는 뜻이고, 그건 잠금이 둘을
         * 가르지 않았다는 뜻이다.
         *
         * <p><b>"성립 횟수"로 세려면 기대값이 1이어야 한다.</b> 마지막에 도착한 쪽은 자기가 카운트를
         * 0으로 만들므로 직렬화되든 안 되든 늘 성립한다 — 즉 1 대 2로 갈리고 0이 될 수는 없다.
         * 판별력 자체는 있지만(실제로 그렇게 썼다가 기대값을 0으로 잡아 깨뜨렸다) 이름이 뜻을 바로
         * 말해 주지 않아 이쪽을 고른다.
         *
         * <p>경과 시간으로도 같은 사실을 짚을 수 있지만(성립 ~58ms vs 상한 ~2000ms) 임계값을 골라야
         * 하고 러너 속도에 기댄다. 이 셈은 고를 것이 없다.
         */
        int rendezvousWaitedInVain() {
            return waitedInVain.get();
        }

        @Override
        public boolean appendIfAbsent(ChatKickLog log) {
            boolean inserted = delegate.appendIfAbsent(log);
            CountDownLatch latch = recorded;
            if (latch != null) {
                latch.countDown();
                try {
                    if (!latch.await(RENDEZVOUS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                        waitedInVain.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return inserted;
        }

        @Override
        public long countDistinctKickersSince(UUID userId, Instant since) {
            return delegate.countDistinctKickersSince(userId, since);
        }
    }

    /**
     * 상대를 기다리는 상한.
     *
     * <p><b>{@code ChatBanJpaRepository.LOCK_WAIT_LIMIT}보다 작아야 한다.</b> 초록불 경로에서 뒤에 온
     * 쪽은 이 시간만큼 잠금에서 기다리는데, 그 대기에도 상한이 걸려 있어서다 — 이 값이 그보다 크면
     * 뒤쪽이 레이스가 아니라 잠금 타임아웃으로 죽고, 테스트는 "강퇴 자체가 실패했다"로 깨진다(실제로
     * 2초였을 때 그렇게 깨졌다). 트랜잭션 상한(5초)보다 작아야 하는 조건도 같은 이유로 여전히 유효하다.
     *
     * <p>덮어야 하는 것은 "깨진 코드에서 두 스레드가 기록에 도달하는 시차"뿐이고 그건 밀리초 규모다 —
     * {@code given}의 첫 {@code record}가 이미 JPA·커넥션·쿼리 경로를 워밍한다. 200ms면 잠금 상한
     * 1초에 대해 5배 여유다(실측: 본 테스트 0.348초, 전에 2초였을 때 2.42초).
     */
    private static final Duration RENDEZVOUS_TIMEOUT = Duration.ofMillis(200);

    @TestConfiguration
    static class Wiring {
        @Bean
        LatchedKickLogRepository chatKickLogRepository(ChatKickLogJpaRepository jpa) {
            return new LatchedKickLogRepository(new ChatKickLogRepositoryImpl(jpa));
        }

        @Bean
        ChatBanStateRepository chatBanStateRepository(ChatBanJpaRepository jpa) {
            return new ChatBanStateRepositoryImpl(jpa);
        }

        @Bean
        ChatKickRecorder chatKickRecorder(ChatKickLogRepository logs, ChatBanStateRepository bans) {
            return new ChatKickRecorder(logs, bans);
        }
    }

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
    private ChatKickRecorder recorder;
    @Autowired
    private ChatKickLogRepository kickLogs;
    @Autowired
    private ChatBanStateRepository bans;
    @Autowired
    private LatchedKickLogRepository latched;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(730);

    /** 대상은 테스트 인스턴스마다 새로 만든다 — 이 스위트는 {@code NOT_SUPPORTED}라 롤백이 없다. */
    private final UUID targetUserId = UUID.randomUUID();

    private ChatKickLog kick(UUID roomId, UUID kickedById) {
        return new ChatKickLog(targetUserId, roomId, kickedById, ChatRole.SELLER, "문제된 원문", NOW);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 서로 다른 방에서 동시에 강퇴해도 세 번째 확증에서 밴이 걸린다")
    void concurrentKicksInDifferentRoomsDoNotSkipTheThreshold() throws Exception {
        // given: 확증 1명이 이미 커밋돼 있다. 여기서 서로 다른 두 사람이 각자 다른 방에서 동시에
        // 강퇴하면 확증은 3이 되고, 임계가 3이라 밴이 걸려야 한다.
        recorder.record(kick(UUID.randomUUID(), UUID.randomUUID()));
        assertThat(kickLogs.countDistinctKickersSince(targetUserId, NOW.minus(WINDOW))).isEqualTo(1);

        CountDownLatch bothRecorded = new CountDownLatch(2);
        latched.arm(bothRecorded);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // when: 둘이 겹친다. 데코레이터가 기록과 집계 사이에 창을 열어, 직렬화가 없으면 둘이 서로를
        // 만나 매번 같은(결함이 드러나는) 순서가 되게 한다.
        try {
            Thread a = kicker(failures);
            Thread b = kicker(failures);
            a.start();
            b.start();
            assertThat(a.join(Duration.ofSeconds(30))).as("강퇴 A가 30초 안에 끝나지 않았다").isTrue();
            assertThat(b.join(Duration.ofSeconds(30))).as("강퇴 B가 30초 안에 끝나지 않았다").isTrue();
        } finally {
            latched.disarm();
        }

        // then: 두 강퇴 모두 성공했고(실패를 밴 부재로 착각하지 않도록 먼저 본다) 확증은 3이다.
        assertThat(failures).as("강퇴 자체가 실패했다면 밴 부재의 원인이 레이스가 아니다").isEmpty();
        assertThat(kickLogs.countDistinctKickersSince(targetUserId, NOW.minus(WINDOW)))
                .as("서로 다른 방이라 세 행이 모두 남아야 한다")
                .isEqualTo(3);

        // 같은 대상이므로 둘은 서로를 만나지 못해야 한다 — 만났다면 둘 다 기록에 도달했다는 뜻이고,
        // 그건 잠금이 둘을 가르지 않았다는 뜻이다. 아래 밴 단언이 결과를 보는 것이고 이것은 기제를 본다.
        assertThat(latched.rendezvousWaitedInVain())
                .as("같은 대상인데 아무도 헛되게 기다리지 않았다 — 밴 판단이 직렬화되지 않았다")
                .isEqualTo(1);

        // 임계를 넘겼으므로 밴이 있어야 한다. 없으면 두 트랜잭션이 각자 "기존 1 + 나" 만 세고
        // 임계를 건너뛴 것이다 — 아무도 그 사용자를 다시 강퇴하지 않으면 그 확증 3은 영영 집행되지 않는다.
        assertThat(bans.findActive(targetUserId, NOW))
                .as("확증 3명인데 밴이 없다 — 동시 강퇴가 임계를 건너뛰었다")
                .isPresent();
        assertThat(bans.findActive(targetUserId, NOW).orElseThrow().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("⭐ 잠금이 실제로 잡힌다 — 경계가 없으면 예외 없이 아무것도 안 잠근다")
    void theLockIsActuallyHeldInsideTheBoundary() {
        // given: 이 테스트만 @DataJpaTest 가 열어 주는 트랜잭션을 그대로 쓴다(NOT_SUPPORTED 가 없다).
        // 경계가 있으면 잠금이 그 트랜잭션이 끝날 때까지 남아 있어야 한다.

        // when
        bans.lockForBanDecision(targetUserId);

        // then: 같은 백엔드가 그 키의 어드바이저리 잠금을 쥐고 있다. 잠금 호출을 지우면 0건이 된다.
        //
        // 경계가 없을 때의 이야기는 <b>바뀌었다</b>. 전에는 이 SELECT 가 조용히 0을 보호했는데,
        // 대기 상한을 거는 @Modifying 문장이 그 앞에 생긴 뒤로는 경계 없이 부르면
        // InvalidDataAccessApiUsageException("No active transaction for update or delete query")로
        // 시끄럽게 죽는다(세 리뷰어 각자 실측). 그 안전망은 <b>그 문장이 앞에 있다는 사실</b>에
        // 걸려 있으므로, 두 문장을 하나로 "정리"하면 함께 사라진다.
        //
        // 키 비교까지 하는 이유: 잠금이 잡혔다는 것만 보면 키 유도를 바꾸는 변경을 놓친다.
        // pg_locks 는 64비트 키를 classid/objid 두 조각으로 쪼개 보여 주므로 다시 붙여 비교한다.
        //
        // ⚠️ seed 는 운영 코드와 <b>같은 상수를 읽는다</b> — 그래서 이 단언은 seed 값을 고정하지
        // 않는다(바꾸면 양쪽이 같이 바뀐다). 고정하는 것은 키가 <b>그 사용자로부터</b> 유도된다는
        // 모양이고, seed 는 다른 용도와 겹치지 않기만 하면 되는 자유 파라미터라 그게 맞다.
        // 키가 사용자와 무관해지는 회귀는 바로 아래 "잠금은 사용자 단위다"가 잡는다.
        Object held = entityManager.createNativeQuery("""
                SELECT count(*) FROM pg_locks
                 WHERE locktype = 'advisory'
                   AND pid = pg_backend_pid()
                   AND ((classid::bigint << 32) | objid::bigint)
                       = hashtextextended(cast(:userId as text), """ + ChatBanJpaRepository.BAN_DECISION_LOCK_SEED + """
                )
                """)
                .setParameter("userId", targetUserId.toString())
                .getSingleResult();

        assertThat(((Number) held).longValue())
                .as("경계 안인데 그 사용자 키의 어드바이저리 잠금이 없다 — 직렬화가 조용히 사라졌다")
                .isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 잠금은 사용자 단위다 — 서로 다른 대상은 줄을 서지 않는다")
    void theLockIsScopedToTheTargetUser() throws Exception {
        // given: 서로 다른 두 사람을 동시에 강퇴한다. 밴 판단이 사용자 단위로 갈려 있으면 두
        // 트랜잭션은 서로 무관하므로 둘 다 기록에 도달해 랑데부가 성립한다.
        //
        // 이 축을 재는 테스트가 없으면 잠금 키를 <b>상수</b>로 바꿔도 아무것도 깨지지 않는다 —
        // 위 테스트는 대상이 한 명뿐이라 전역 단일 잠금이어도 직렬화가 똑같이 일어나고 밴도 똑같이
        // 걸린다. 그러면 모든 강퇴가 플랫폼 전역으로 줄을 서고 각자 최대 상한까지 커넥션을 쥐는
        // 회귀가 조용히 들어온다.
        UUID otherTarget = UUID.randomUUID();
        // 경로를 먼저 덥힌다 — 첫 record 는 JPA·커넥션·쿼리 준비를 함께 타므로, 덥히지 않으면
        // 느린 쪽이 랑데부 상한을 넘겨 "줄을 섰다"로 잘못 읽힌다. 확증 1명은 임계 미달이라 밴은 없다.
        recorder.record(new ChatKickLog(targetUserId, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "워밍업", NOW));
        recorder.record(new ChatKickLog(otherTarget, UUID.randomUUID(), UUID.randomUUID(),
                ChatRole.SELLER, "워밍업", NOW));

        CountDownLatch bothRecorded = new CountDownLatch(2);
        latched.arm(bothRecorded);
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        // when
        try {
            Thread a = kickerOf(targetUserId, failures);
            Thread b = kickerOf(otherTarget, failures);
            a.start();
            b.start();
            assertThat(a.join(Duration.ofSeconds(30))).as("강퇴 A가 30초 안에 끝나지 않았다").isTrue();
            assertThat(b.join(Duration.ofSeconds(30))).as("강퇴 B가 30초 안에 끝나지 않았다").isTrue();
        } finally {
            latched.disarm();
        }

        // then: 둘이 서로를 만났다 — 즉 아무도 상대의 잠금을 기다리지 않았다.
        // 상수 키로 바뀌면 한쪽이 기록에 닿기도 전에 멈춰 랑데부가 상한에 걸리고 이 단언이 깨진다
        // (실측 마진: 성립 ~58ms vs 상한 ~2000ms. 그래서 경과 시간이 아니라 성립 여부로 본다).
        assertThat(failures).as("강퇴 자체가 실패했다면 랑데부 결과의 원인이 잠금이 아니다").isEmpty();
        assertThat(latched.rendezvousWaitedInVain())
                .as("서로 다른 대상이 줄을 섰다 — 잠금 키가 사용자 단위가 아니다")
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 잠금 대기는 상한에서 끊기고 재시도 가능한 도메인 예외가 된다 — 커넥션을 오래 쥐지 않는다")
    void lockWaitIsBoundedAndTranslated() throws Exception {
        // given: 누군가 이 사용자의 잠금을 상한보다 오래 쥐고 있다. 운영에서는 강퇴 엔드포인트에
        // 레이트리밋이 없어 같은 사용자로 요청을 겹쳐 쌓을 수 있고, 기다리는 요청은 기다리는 동안에도
        // 커넥션을 쥔다(잠금이 트랜잭션 첫 문이라 커넥션이 이미 획득된 상태다). 상한이 없으면 보유
        // 시간이 '작업 시간'에서 '대기열 깊이 × 작업 시간'으로 바뀌어 풀이 마른다.
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> {
                bans.lockForBanDecision(targetUserId);
                held.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        });
        holder.setDaemon(true);
        holder.start();
        assertThat(held.await(10, TimeUnit.SECONDS)).as("보유자가 잠금을 잡지 못했다").isTrue();

        // when
        long startedAt = System.nanoTime();
        Throwable thrown;
        long waitedMillis;
        try {
            thrown = catchThrowable(() -> recorder.record(kick(UUID.randomUUID(), UUID.randomUUID())));
        } finally {
            // 단언보다 먼저, 그리고 실패해도 반드시 놓는다 — 안 놓으면 보유자 데몬이 자기 await 동안
            // 커넥션과 잠금을 쥔 채 남아 뒤따르는 테스트를 느리게 만든다.
            waitedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            release.countDown();
        }

        // then: 무한정 기다리지 않고 끊긴다. 상한을 지우면 트랜잭션 타임아웃(5초)까지 버티므로
        // 이 단언이 그 차이를 가른다 — 잠금 뒤에는 쿼리 셋과 커밋뿐이라 정상 경로는 밀리초다.
        assertThat(waitedMillis)
                .as("잠금 대기가 상한에서 끊기지 않았다 — 대기열이 커넥션을 오래 쥔다")
                .isLessThan(TRANSACTION_TIMEOUT.toMillis());

        // 그리고 500이 아니라 재시도 가능한 도메인 예외로 나간다. 실측으로 Postgres가
        // 55P03(lock_not_available)을 주고 Hibernate LockTimeoutException을 지나
        // CannotAcquireLockException으로 번역되는데, 그 타입이 QueryTimeoutException과 계보가 달라
        // 호출자 catch에 함께 열거되지 않으면 그대로 500이 된다.
        // 그리고 올라오는 타입이 무엇인지 여기서 고정한다. 실측으로 Postgres가 55P03(lock_not_available)을
        // 주고 Hibernate LockTimeoutException을 지나 이 타입으로 번역된다. 이 타입이 QueryTimeoutException과
        // 계보가 달라(ConcurrencyFailureException 밑이다) 호출자 catch에 함께 열거되지 않으면 500이 된다.
        //
        // 번역 자체는 여기서 재지 않는다 — 그건 KickUserService의 몫이고 KickUserServiceTest가 이 타입을
        // 입력으로 받아 409가 되는지 지킨다. 둘을 한 자리에 두면 DB가 바뀔 때 무엇이 틀렸는지 가려진다.
        assertThat(thrown)
                .as("잠금 대기 초과가 예상과 다른 타입으로 올라온다 — 호출자 catch가 못 잡는다")
                .isInstanceOf(CannotAcquireLockException.class);
    }

    /**
     * {@code ChatKickRecorder}의 트랜잭션 상한. 잠금 대기 상한이 이보다 작아야 "대기가 유계다"가
     * 의미를 갖는다 — 같으면 상한을 지워도 증상이 같아 위 단언이 아무것도 가르지 못한다.
     *
     * <p><b>운영 상수를 읽는다.</b> 손으로 복사해 두면 그쪽을 줄일 때 이 가드가 조용히 죽는다 —
     * 상한 제거 뮤턴트의 대기가 줄어든 트랜잭션 상한에서 끊기고, 그 값이 사본보다 작아 단언을
     * 통과한다(실측: 5 → 2로 줄이면 14개 전부 초록).
     */
    private static final Duration TRANSACTION_TIMEOUT =
            Duration.ofSeconds(ChatKickRecorder.TRANSACTION_TIMEOUT_SECONDS);

    private Thread kicker(List<Throwable> failures) {
        return kickerOf(targetUserId, failures);
    }

    /**
     * 지정한 대상을 새 방·새 강퇴자로 한 번 강퇴하는 스레드.
     *
     * <p>daemon이다 — 랑데부 상한이나 잠금 대기에 걸려 멈춘 스레드가 러너의 종료 방식에 영향을
     * 주지 않게 한다. 예외는 삼키지 않고 모아 두어 "강퇴가 실패한 것"과 "강퇴는 됐는데 판정이
     * 틀린 것"이 단언에서 갈리게 한다.
     */
    private Thread kickerOf(UUID target, List<Throwable> failures) {
        Thread thread = new Thread(() -> {
            try {
                recorder.record(new ChatKickLog(target, UUID.randomUUID(), UUID.randomUUID(),
                        ChatRole.SELLER, "문제된 원문", NOW));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
        thread.setDaemon(true);
        return thread;
    }
}
