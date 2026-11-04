package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        // 스키마는 아래 Flyway 파일이 만든다. validate는 그 결과와 엔티티 매핑을 대조하는 역할이다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void applyRealSchema() throws Exception {
        LiveSchema.applyTo(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }


    @Autowired
    private ChatKickLogJpaRepository jpaRepository;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

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

    /**
     * ⭐ 방 단위 조회가 <b>순차 스캔이 아님</b>을 계획으로 확인한다.
     *
     * <p>이 javadoc 이 한때 "이 질의에 쓸 인덱스가 없다 … 즉 순차 스캔이다 … live 담당자에게 넘긴
     * 항목이다" 로 적혀 있었다. <b>그 사이에 들어왔다</b> — SPR-151 의
     * {@code V202611011635__chat_kick_log_room_index.sql} 이 {@code (live_room_id, user_id)} 를
     * 세웠고, 그 마이그레이션 주석이 바로 이 질의의 Index Only Scan 을 근거로 든다. 수치를 적는
     * 대신 <b>계획을 재는 테스트</b>로 바꾼 이유가 그것이다 — 주석은 낡지만 이 단언은 인덱스가
     * 사라지는 날 깨진다.
     *
     * <p>이 질의에 거는 비용이 두 곳에서 는다 — 이력 조회가 요청마다 부르고(무인증 공개 경로),
     * 강퇴 명단 재적재가 복구 때 부른다. 순차 스캔이면 비용이 그 방이 아니라 <b>플랫폼 전체 누적
     * 강퇴 수</b>에 비례한다.
     *
     * <p>행이 적으면 플래너가 순차 스캔을 고를 수 있으므로 {@code enable_seqscan = off} 로
     * <b>인덱스가 존재하는지</b>를 묻는다 — 재는 것은 플래너의 선택이 아니라 선택지의 존재다.
     */
    @Test
    @DisplayName("⭐ 방 단위 강퇴자 조회는 인덱스를 쓴다 — 없으면 비용이 플랫폼 전체 누적에 비례한다")
    void roomScopedLookupHasAnIndex() {
        // given
        repository().appendIfAbsent(log(roomId, targetUserId));
        entityManager.createNativeQuery("SET LOCAL enable_seqscan = off").executeUpdate();

        // when
        String plan = entityManager.createNativeQuery(
                        "EXPLAIN SELECT user_id FROM live_schema.chat_kick_log WHERE live_room_id = :r")
                .setParameter("r", roomId)
                .getResultList().toString();

        // then
        assertThat(plan)
                .as("계획에 인덱스가 없다 — (live_room_id, ...) 인덱스가 사라졌다. 계획: %s", plan)
                .containsIgnoringCase("Index")
                .contains("chat_kick_log");
    }
}
