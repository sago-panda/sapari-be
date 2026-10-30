package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;

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
     * <p>{@code arm}한 래치가 있으면 {@code appendIfAbsent} 직후에 상대를 기다린다 — 그래야 두 트랜잭션이
     * <b>둘 다 기록한 뒤에</b> 집계에 들어가고, 운영에서 드물게 겹치는 순간이 매번 재현된다.
     *
     * <p><b>기다림에 상한이 있는 것이 핵심이다.</b> 사용자 단위로 직렬화하면 한쪽은 기록에 닿기도 전에
     * 잠금에서 멈추므로 영원히 오지 않는다. 상한이 없으면 고친 코드가 여기서 교착한다 — 즉 이 테스트가
     * 수정 후에 통과하려면 상한이 있어야 한다. 상한이 지나면 그냥 진행하고, 그때는 앞선 쪽이 커밋하며
     * 잠금을 놓아 뒤쪽이 온전한 수를 센다.
     */
    static class LatchedKickLogRepository implements ChatKickLogRepository {

        private final ChatKickLogRepository delegate;
        private volatile CountDownLatch recorded;

        LatchedKickLogRepository(ChatKickLogRepository delegate) {
            this.delegate = delegate;
        }

        void arm(CountDownLatch latch) {
            this.recorded = latch;
        }

        void disarm() {
            this.recorded = null;
        }

        @Override
        public boolean appendIfAbsent(ChatKickLog log) {
            boolean inserted = delegate.appendIfAbsent(log);
            CountDownLatch latch = recorded;
            if (latch != null) {
                latch.countDown();
                try {
                    latch.await(RENDEZVOUS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
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
     * 상대를 기다리는 상한. {@code ChatKickRecorder}의 트랜잭션 상한(5초)보다 넉넉히 작아야 한다 —
     * 이 대기는 그 트랜잭션 안에서 일어나므로, 둘을 더한 값이 상한을 넘으면 테스트가 레이스가 아니라
     * 타임아웃으로 깨진다.
     */
    private static final Duration RENDEZVOUS_TIMEOUT = Duration.ofSeconds(2);

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

        // when: 둘이 겹친다. 데코레이터가 "둘 다 기록한 뒤에 센다"를 만들어 매번 같은 순서가 되게 한다.
        try {
            Thread a = kicker(failures);
            Thread b = kicker(failures);
            a.start();
            b.start();
            a.join(TimeUnit.SECONDS.toMillis(30));
            b.join(TimeUnit.SECONDS.toMillis(30));
        } finally {
            latched.disarm();
        }

        // then: 두 강퇴 모두 성공했고(실패를 밴 부재로 착각하지 않도록 먼저 본다) 확증은 3이다.
        assertThat(failures).as("강퇴 자체가 실패했다면 밴 부재의 원인이 레이스가 아니다").isEmpty();
        assertThat(kickLogs.countDistinctKickersSince(targetUserId, NOW.minus(WINDOW)))
                .as("서로 다른 방이라 세 행이 모두 남아야 한다")
                .isEqualTo(3);

        // 임계를 넘겼으므로 밴이 있어야 한다. 없으면 두 트랜잭션이 각자 "기존 1 + 나" 만 세고
        // 임계를 건너뛴 것이다 — 아무도 그 사용자를 다시 강퇴하지 않으면 그 확증 3은 영영 집행되지 않는다.
        assertThat(bans.findActive(targetUserId, NOW))
                .as("확증 3명인데 밴이 없다 — 동시 강퇴가 임계를 건너뛰었다")
                .isPresent();
        assertThat(bans.findActive(targetUserId, NOW).orElseThrow().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    private Thread kicker(List<Throwable> failures) {
        return new Thread(() -> {
            try {
                recorder.record(kick(UUID.randomUUID(), UUID.randomUUID()));
            } catch (Throwable t) {
                failures.add(t);
            }
        });
    }
}
