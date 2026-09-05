package com.sapari.chat.infrastructure.persistence.repository;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
        String ddl = Files.readString(repositoryRoot().resolve("db/migration/live/V1__init_live.sql"));
        try (Connection connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    /** 테스트 작업 디렉터리는 모듈이라 저장소 루트까지 올라간다 — settings.gradle이 그 표지다. */
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
    private ChatBanJpaRepository banJpaRepository;
    @Autowired
    private ChatKickLogJpaRepository kickLogJpaRepository;

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
        repository().append(new ChatBan(userId, SYSTEM, NOW.minus(Duration.ofDays(1)),
                NOW.minus(Duration.ofDays(8))));

        // when & then
        assertThat(repository().findActive(userId, NOW)).isEmpty();
    }

    @Test
    @DisplayName("만료가 남은 밴은 활성이다")
    void unexpiredBanIsActive() {
        // given
        Instant expiry = NOW.plus(Duration.ofDays(3));
        repository().append(new ChatBan(userId, SYSTEM, expiry, NOW.minus(Duration.ofDays(4))));

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
        repository().append(new ChatBan(userId, SYSTEM, null, NOW.minus(Duration.ofDays(1))));

        // when
        ChatBan found = repository().findActive(userId, NOW).orElseThrow();

        // then
        assertThat(found.isPermanent()).isTrue();
    }

    @Test
    @DisplayName("⭐ 여러 밴이 겹치면 가장 오래 가는 것을 준다 — 짧은 쪽을 집으면 미러가 일찍 풀린다")
    void picksTheLongestLivingBan() {
        // given: 자동 밴 위에 관리자가 더 긴 밴을 얹은 모양
        Instant shortExpiry = NOW.plus(Duration.ofDays(3));
        Instant longExpiry = NOW.plus(Duration.ofDays(30));
        repository().append(new ChatBan(userId, SYSTEM, shortExpiry, NOW.minus(Duration.ofDays(4))));
        repository().append(new ChatBan(userId, UUID.randomUUID(), longExpiry, NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW).orElseThrow().expiresAt()).isEqualTo(longExpiry);
    }

    @Test
    @DisplayName("⭐ 영구 밴은 어떤 기한부 밴보다 오래 간다 — NULL이 정렬 뒤로 밀리면 안 된다")
    void permanentOutranksAnyDatedBan() {
        // given
        repository().append(new ChatBan(userId, SYSTEM, NOW.plus(Duration.ofDays(365)),
                NOW.minus(Duration.ofDays(1))));
        repository().append(new ChatBan(userId, SYSTEM, null, NOW.minus(Duration.ofDays(1))));

        // when & then
        assertThat(repository().findActive(userId, NOW).orElseThrow().isPermanent()).isTrue();
    }

    @Test
    @DisplayName("남의 밴은 보이지 않는다")
    void otherUsersBanIsInvisible() {
        // given
        repository().append(new ChatBan(UUID.randomUUID(), SYSTEM, NOW.plus(Duration.ofDays(3)),
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
