package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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

import com.sapari.chat.domain.model.ChatKickLog;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatKickLogRepository;

/**
 * 강퇴 로그의 <b>멱등</b>을 실제 Postgres에서 고정한다.
 *
 * <p>이 보장은 애플리케이션 코드가 아니라 테이블의 {@code UNIQUE(user_id, live_room_id)}와
 * {@code ON CONFLICT DO NOTHING}이 함께 만든다. 목으로는 아무것도 검증되지 않아 컨테이너를 띄운다.
 *
 * <p>스키마는 <b>운영과 같은 Flyway 파일을 그대로</b> 실행해 만든다. 테스트에 DDL을 다시 적으면 두 벌이
 * 되고, 어긋나는 순간 초록인 채로 어긋난다. 덤으로 {@code ddl-auto=validate}가 엔티티와 실제 컬럼을
 * 대조하므로 매핑이 틀리면 컨텍스트가 아예 못 뜬다.
 *
 * <p>슬라이스({@code @DataJpaTest})를 쓰는 이유는 확인 대상이 Postgres 제약 하나라서다. 전체 컨텍스트를 띄우면
 * Mongo·Redis 어댑터까지 함께 살아나 이 테스트와 무관한 컨테이너 두 개를 더 요구한다. 슬라이스는 덤으로
 * 쓰기 쿼리에 필요한 트랜잭션 경계도 제공한다 — 포트 자신은 경계를 열지 않는다(그 경계는 유스케이스 몫이고,
 * 강퇴 흐름은 이 커밋이 확정된 다음에야 Redis로 넘어가야 한다).
 *
 * <p>{@code replace = NONE}이 없으면 슬라이스가 DataSource를 내장 DB로 갈아끼워 컨테이너가 무시된다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("ChatKickLogRepository — 중복 강퇴는 무동작")
class ChatKickLogRepositoryImplTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        // 스키마는 아래 Flyway 파일이 만든다. validate는 그 결과와 엔티티 매핑을 대조하는 역할이다.
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
    private ChatKickLogJpaRepository jpaRepository;

    /** 슬라이스는 Spring Data 리포지토리만 올리고 {@code @Repository} 컴포넌트는 스캔하지 않아 직접 엮는다. */
    private ChatKickLogRepository repository() {
        return new ChatKickLogRepositoryImpl(jpaRepository);
    }

    private final UUID roomId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private final Instant kickedAt = Instant.parse("2026-08-11T00:00:00Z");

    private ChatKickLog log(UUID room, UUID target) {
        return new ChatKickLog(target, room, UUID.randomUUID(), ChatRole.SELLER, "문제된 원문", kickedAt);
    }

    @Test
    @DisplayName("처음 기록되면 true — 이 신호가 밴 카운트를 올릴지 정한다")
    void firstAppendReportsInserted() {
        // when & then
        assertThat(repository().appendIfAbsent(log(roomId, targetUserId))).isTrue();
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 방·같은 유저를 다시 기록하면 false, 행은 하나뿐 — 재시도가 밴 카운트를 밀어 올리지 않는다")
    void duplicateAppendIsNoop() {
        // given
        assertThat(repository().appendIfAbsent(log(roomId, targetUserId))).isTrue();

        // when: 발행 실패로 운영자가 같은 강퇴를 다시 눌렀다고 치자
        boolean second = repository().appendIfAbsent(log(roomId, targetUserId));

        // then: ON CONFLICT DO NOTHING을 빼면 제약 위반 예외가 터져 이 단언이 깨진다
        assertThat(second).isFalse();
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("방이 다르면 같은 유저도 다시 기록된다 — 유니크 축이 (유저, 방)이지 유저가 아니다")
    void sameUserInAnotherRoomIsRecorded() {
        // given
        assertThat(repository().appendIfAbsent(log(roomId, targetUserId))).isTrue();

        // when & then: 누적 강퇴가 방마다 쌓여야 밴 에스컬레이션이 성립한다
        assertThat(repository().appendIfAbsent(log(UUID.randomUUID(), targetUserId))).isTrue();
        assertThat(jpaRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("kicked_at은 넘긴 값 그대로 — DB 기본값(now())에 맡기지 않는다")
    void kickedAtComesFromCaller() {
        // when
        repository().appendIfAbsent(log(roomId, targetUserId));

        // then: 기본값에 맡기면 주입된 시계로 누적 강퇴 2년 창을 고정할 수 없다
        assertThat(jpaRepository.findAll())
                .singleElement()
                .satisfies(entity -> assertThat(entity.getKickedAt()).isEqualTo(kickedAt));
    }
}
