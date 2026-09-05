package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatBan;
import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatBanStateRepository;
import com.sapari.chat.domain.repository.ChatKickLogRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatBanStateRepositoryImpl;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogJpaRepository;
import com.sapari.chat.infrastructure.persistence.repository.ChatKickLogRepositoryImpl;

/**
 * 강퇴의 DB 쓰기가 <b>트랜잭션 밖에서 호출돼도</b> 성립하는지 실제 Postgres에서 고정한다.
 *
 * <p>이 테스트가 존재하는 이유가 회귀 하나다. 두 쓰기가 모두 `@Modifying` 네이티브 INSERT인데,
 * Spring Data는 리포지토리 인터페이스에 직접 선언된 그런 쿼리에 기본 트랜잭션을 얹어 주지 않는다.
 * 유스케이스가 경계를 열지 않던 시절 이 경로는 <b>운영에서 100% 실패했다</b> —
 * {@code InvalidDataAccessApiUsageException: No active transaction for update or delete query}.
 *
 * <p><b>기존 테스트가 왜 못 잡았는지가 요점이다.</b> 서비스 테스트는 전부 목이라 진짜 쿼리가 돌지 않고,
 * 리포지토리 테스트는 {@code @DataJpaTest}가 트랜잭션을 <b>대신 열어 준다</b>. 둘 다 초록인 채로 운영만
 * 깨졌다. 그래서 여기서는 {@code Propagation.NOT_SUPPORTED}로 그 도움을 명시적으로 걷어내고, 실제 호출자와
 * 같은 조건(트랜잭션 없음)에서 부른다.
 *
 * <p>경계를 여는 것은 {@link ChatKickRecorder}의 {@code @Transactional}이고, 그게 걸리려면 <b>프록시를
 * 타야 한다</b>. 그래서 여기서도 손으로 {@code new} 하지 않고 컨텍스트에서 주입받는다 — 직접 생성하면
 * 프록시가 없어 이 테스트가 검증하려는 바로 그 장치가 빠진다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(ChatKickRecorderTest.Wiring.class)
@DisplayName("ChatKickRecorder — 트랜잭션 밖에서 불러도 기록된다")
class ChatKickRecorderTest {

    @TestConfiguration
    static class Wiring {
        @Bean
        ChatKickLogRepository chatKickLogRepository(ChatKickLogJpaRepository jpa) {
            return new ChatKickLogRepositoryImpl(jpa);
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
        String ddl = Files.readString(repositoryRoot().resolve("db/migration/live/V1__init_live.sql"));
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static Path repositoryRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("settings.gradle"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("저장소 루트를 찾지 못했다 — 시작 위치=" + here);
    }

    @Autowired
    private ChatKickRecorder recorder;
    @Autowired
    private ChatKickLogRepository kickLogs;
    @Autowired
    private ChatBanStateRepository bans;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private final UUID targetUserId = UUID.randomUUID();

    private ChatKickLog kick(UUID roomId, Instant kickedAt) {
        return new ChatKickLog(targetUserId, roomId, UUID.randomUUID(),
                ChatRole.SELLER, "문제된 원문", kickedAt);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 트랜잭션 없이 불러도 기록된다 — 경계를 안 열면 여기서 예외로 죽는다")
    void recordsWithoutAnAmbientTransaction() {
        // given: 호출자가 트랜잭션을 열지 않은, 실제 유스케이스와 같은 조건

        // when
        recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 예외 없이 커밋됐고, 누적 1회는 임계 미만이라 밴은 없다
        assertThat(bans.findActive(targetUserId, NOW)).isEmpty();
        assertThat(kickLogs.countSince(targetUserId, NOW.minus(Duration.ofDays(730)))).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("3회째에 1주 밴이 함께 커밋된다 — 로그와 밴이 같은 트랜잭션이다")
    void escalatesOnTheThirdKick() {
        // given: 서로 다른 방에서 두 번
        recorder.record(kick(UUID.randomUUID(), NOW));
        recorder.record(kick(UUID.randomUUID(), NOW));

        // when
        recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 정본에 1주 밴이 남는다 — 미러는 호출자가 이 값을 다시 읽어 쓴다
        ChatBan active = bans.findActive(targetUserId, NOW).orElseThrow();
        assertThat(active.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 방 재강퇴는 누적을 늘리지 않는다 — 반복 강퇴로 밴을 연장할 수 없다")
    void duplicateKickDoesNotCount() {
        // given: 같은 방에서 두 번, 다른 방에서 한 번 = 실제 누적 2
        UUID room = UUID.randomUUID();
        recorder.record(kick(room, NOW));
        recorder.record(kick(room, NOW));
        recorder.record(kick(UUID.randomUUID(), NOW));

        // when & then: 3회를 불렀지만 임계에 닿지 않는다
        assertThat(kickLogs.countSince(targetUserId, NOW.minus(Duration.ofDays(730)))).isEqualTo(2);
        assertThat(bans.findActive(targetUserId, NOW)).isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("이미 밴이 있으면 겹쳐 쌓지 않는다 — 재시도가 밴을 늘리지 않는다")
    void existingBanIsReturnedNotStacked() {
        // given: 이미 활성 밴
        // ⚠️ append도 @Modifying이라 경계가 필요하다. 운영에서는 recorder가 열어 주지만 여기서는
        //    준비 코드라 직접 연다 — 이 테스트가 일부러 주변 트랜잭션을 걷어냈기 때문이다.
        Instant expiry = NOW.plus(Duration.ofDays(30));
        transactionTemplate.executeWithoutResult(status -> bans.append(
                new ChatBan(targetUserId, UUID.randomUUID(), expiry, NOW.minus(Duration.ofDays(1)))));

        // when
        recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 새로 쌓지 않았으므로 원래 밴 그대로다
        assertThat(bans.findActive(targetUserId, NOW).orElseThrow().expiresAt()).isEqualTo(expiry);
    }
}
