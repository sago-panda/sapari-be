package com.sapari.chat.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
    @Autowired
    private ChatBanJpaRepository banJpaRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    private final UUID targetUserId = UUID.randomUUID();

    /**
     * 이 사용자의 밴만 지워 "걸렸다가 만료됐다"와 같은 상태를 만든다. 해제 경로가 아직 없어 직접 지운다.
     *
     * <p>테이블 전체가 아니라 대상만 지우는 이유는 이 스위트가 {@code NOT_SUPPORTED}라 <b>롤백이 없어</b>
     * 같은 DB를 다른 테스트와 공유하기 때문이다.
     */
    private void deleteBansOfTarget() {
        banJpaRepository.deleteAll(banJpaRepository.findActive(
                targetUserId, NOW.minus(java.time.Duration.ofDays(3650)), Limit.of(100)));
    }

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
        Optional<ChatBan> ban = recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 예외 없이 커밋됐고, 누적 1회는 임계 미만이라 밴은 없다
        assertThat(ban).isEmpty();
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
        Optional<ChatBan> ban = recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 돌려받은 값과 정본이 같은 밴이다 — 호출자는 이걸 미러에 쓴다
        assertThat(ban).isPresent();
        assertThat(ban.get().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(bans.findActive(targetUserId, NOW).orElseThrow().expiresAt())
                .isEqualTo(NOW.plus(Duration.ofDays(7)));
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

    /**
     * ⭐ 밴 쓰기가 실패하면 <b>강퇴 로그도 함께 롤백된다.</b>
     *
     * <p>이 경계가 생기기 전에는 구멍이었다 — 로그는 커밋됐는데 밴이 실패하면 재시도가 중복 경로로 들어가
     * 승격을 영영 건너뛰었다. 트랜잭션이 그걸 닫았고 문서도 그렇게 적었는데, <b>그 보장만 테스트가
     * 없었다.</b> 전파를 {@code REQUIRED}로 되돌리거나 경계를 잃으면 여기서 드러난다.
     *
     * <p>밴 저장소를 던지는 것으로 갈아 끼워 재현한다 — 실제 실패를 만드는 다른 방법이 없다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 밴 쓰기가 실패하면 강퇴 로그도 롤백된다 — 재시도가 처음부터 다시 한다")
    void failedBanRollsBackTheKickLog() {
        // given: 누적 2회. 다음 강퇴가 임계에 닿아 승격을 시도한다
        recorder.record(kick(UUID.randomUUID(), NOW));
        recorder.record(kick(UUID.randomUUID(), NOW));
        long before = kickLogs.countSince(targetUserId, NOW.minus(Duration.ofDays(730)));
        assertThat(before).isEqualTo(2);

        // 밴 INSERT만 실패시키는 저장소로 같은 경계를 다시 만든다
        ChatKickRecorder failing = new ChatKickRecorder(kickLogs, new ChatBanStateRepository() {
            @Override
            public Optional<ChatBan> findActive(UUID userId, Instant now) {
                return bans.findActive(userId, now);
            }

            @Override
            public void append(ChatBan ban) {
                throw new IllegalStateException("밴 저장 실패");
            }
        });
        // 경계는 템플릿으로 만든다 — 여기서 재는 것은 "두 쓰기가 한 트랜잭션인가"이고,
        // 애너테이션이 프록시로 실제로 걸리는지는 ChatModerationWiringTest가 따로 지킨다.
        TransactionTemplate newTx = new TransactionTemplate(transactionManager);
        newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when
        assertThatThrownBy(() -> newTx.executeWithoutResult(status -> failing.record(kick(UUID.randomUUID(), NOW))))
                .isInstanceOf(IllegalStateException.class);

        // then: 강퇴 로그가 남아 있으면 재시도가 중복 경로로 들어가 승격을 영영 건너뛴다
        assertThat(kickLogs.countSince(targetUserId, NOW.minus(Duration.ofDays(730))))
                .as("밴이 실패했는데 강퇴 로그가 커밋됐다 — 재시도가 승격을 건너뛴다")
                .isEqualTo(before);
    }

    /**
     * ⭐ 두 번째 가드({@code !firstKickInThisRoom})가 실제로 일하는 조합.
     *
     * <p>누적이 임계에 닿아 있고 활성 밴은 <b>없어야</b> 한다 — 그래야 "가드가 없으면 승격이 일어난다"가
     * 성립한다. 활성 밴이 있으면 첫 가드가 먼저 걸려 두 번째가 관측되지 않고, 누적이 임계 미만이면
     * 단계 자체가 안 나와 역시 관측되지 않는다. 그 둘이 4차·5차에서 이 가드를 살려 준 조합이었다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 같은 방 재강퇴는 임계에 닿아 있어도 승격하지 않는다 — 재강퇴로 밴을 만들 수 없다")
    void duplicateKickDoesNotEscalateEvenAtThreshold() {
        // given: 다른 방 둘 + 이 방 하나 = 누적 3(임계). 밴이 걸렸다가 만료됐다고 두어 활성 밴은 없다
        UUID room = UUID.randomUUID();
        recorder.record(kick(UUID.randomUUID(), NOW));
        recorder.record(kick(UUID.randomUUID(), NOW));
        recorder.record(kick(room, NOW));
        // 전제를 못 박는다 — 여기서 밴이 안 생겼다면 누적이 임계에 못 미쳤다는 뜻이고, 그러면 아래
        // when 이 가드가 아니라 "단계가 안 나온다"를 재게 된다. 조용히 그렇게 퇴화한 적이 두 번 있다.
        assertThat(bans.findActive(targetUserId, NOW))
                .as("선행 강퇴가 임계에 닿지 않았다 — 이 테스트가 가드를 재지 못한다")
                .isPresent();
        transactionTemplate.executeWithoutResult(status -> deleteBansOfTarget());

        // when: 같은 방을 다시 강퇴한다 — 로그는 이미 있으므로 누적이 늘지 않는다
        Optional<ChatBan> ban = recorder.record(kick(room, NOW));

        // then: 가드가 없으면 여기서 누적 3을 세어 1주 밴이 생긴다
        assertThat(ban)
                .as("중복 강퇴가 승격했다 — 같은 사람을 반복해서 다시 강퇴하는 것만으로 밴이 걸린다")
                .isEmpty();
        assertThat(bans.findActive(targetUserId, NOW)).isEmpty();
    }

    /**
     * ⭐ 두 가드의 <b>순서</b>가 자가치유를 만든다.
     *
     * <p>활성 밴 확인이 중복 검사보다 <b>앞</b>이라, 미러 키가 사라진 사용자를 같은 방에서 다시 강퇴하면
     * 그 밴이 돌아오고 호출자가 미러를 되살린다. 순서가 뒤집히면 중복 강퇴가 {@code empty}로 빠져
     * <b>미러가 영영 복구되지 않는다</b> — 그 사용자는 밴이 정본에 살아 있는데도 어느 방에나 들어간다.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 활성 밴이 있으면 같은 방 재강퇴도 그 밴을 돌려준다 — 미러 복구 경로")
    void duplicateKickStillReturnsActiveBanForMirrorRecovery() {
        // given: 이 방에서 이미 강퇴됐고, 그와 별개로 활성 밴이 있다(미러 키만 유실된 상황)
        UUID room = UUID.randomUUID();
        recorder.record(kick(room, NOW));
        Instant expiry = NOW.plus(Duration.ofDays(30));
        transactionTemplate.executeWithoutResult(status -> bans.append(
                new ChatBan(targetUserId, UUID.randomUUID(), expiry, NOW.minus(Duration.ofDays(1)))));

        // when: 같은 방 재강퇴 — 로그는 no-op이다
        Optional<ChatBan> ban = recorder.record(kick(room, NOW));

        // then: 그래도 밴을 돌려줘야 호출자가 미러를 되살린다
        assertThat(ban)
                .as("중복 강퇴가 빈 값을 돌려줬다 — 미러가 사라진 사용자를 되살릴 경로가 없어진다")
                .isPresent();
        assertThat(ban.get().expiresAt()).isEqualTo(expiry);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("⭐ 이미 밴이 있으면 임계에 닿아도 겹쳐 쌓지 않는다 — 재시도가 밴을 늘리지 않는다")
    void existingBanIsReturnedNotStacked() {
        // given: ⚠️ 가드가 <b>실제로 일하는</b> 조합이어야 한다. 누적이 임계에 닿아 있어야
        //        "가드가 없으면 새 밴이 생긴다"가 성립하고, 그때만 이 테스트가 가드를 잰다.
        //        선행 강퇴 둘(다른 방)로 누적을 2로 만들어 아래 강퇴가 3회째가 되게 한다.
        recorder.record(kick(UUID.randomUUID(), NOW));
        recorder.record(kick(UUID.randomUUID(), NOW));

        // append도 @Modifying이라 경계가 필요하다. 운영에서는 recorder가 열어 주지만 여기서는
        // 준비 코드라 직접 연다 — 이 테스트가 일부러 주변 트랜잭션을 걷어냈기 때문이다.
        Instant expiry = NOW.plus(Duration.ofDays(30));
        transactionTemplate.executeWithoutResult(status -> bans.append(
                new ChatBan(targetUserId, UUID.randomUUID(), expiry, NOW.minus(Duration.ofDays(1)))));

        // when: 3회째 — 가드가 없으면 여기서 1주 밴이 새로 생기고 그것이 반환된다
        Optional<ChatBan> ban = recorder.record(kick(UUID.randomUUID(), NOW));

        // then: 기존 밴을 그대로 돌려주고, 정본에도 새 행이 생기지 않았다
        assertThat(ban).isPresent();
        assertThat(ban.get().expiresAt())
                .as("가드가 통과돼 새 밴이 생겼다 — 재시도마다 밴이 쌓인다")
                .isEqualTo(expiry);
        assertThat(bans.findActive(targetUserId, NOW).orElseThrow().expiresAt()).isEqualTo(expiry);
    }
}
