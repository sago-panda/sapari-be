package com.sapari.chat.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.chat.domain.model.ChatAuditAction;
import com.sapari.chat.domain.model.ChatAuditEntry;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.repository.ChatAuditLogRepository;
import com.sapari.chat.support.LiveSchema;

/**
 * 감사 쓰기를 <b>실제 Postgres</b>에 대고 고정한다. 재는 것은 {@code detail} 칸 하나다.
 *
 * <p>이 저장소에 jsonb 매핑 선례가 <b>0건</b>이라(전수: {@code columnDefinition} 은 모두
 * {@code text}) Hibernate 타입을 들이지 않고 네이티브 쿼리에서 {@code CAST(:detail AS jsonb)} 하는
 * 쪽을 골랐다. 그 선택은 <b>타입 경계를 쿼리 문자열로 옮긴 것</b>이라 컴파일러가 지켜 주지 않는다 —
 * 캐스트가 빠지면 Postgres 가 거부하고, 파라미터가 {@code null} 일 때 타입을 못 정하면 또 거부한다.
 * 둘 다 목으로는 안 드러나므로 컨테이너를 띄운다.
 *
 * <p>스키마는 운영과 같은 Flyway 파일을 그대로 실행한다({@link LiveSchema}). 덤으로
 * {@code ddl-auto=validate} 가 {@code ChatAuditLogEntity} 와 실제 컬럼을 대조하므로, 매핑이 틀리면
 * 컨텍스트가 아예 못 뜬다 — 그 엔티티는 읽기 코드가 없어 이 대조가 유일한 검증이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("ChatAuditLogRepository — detail 은 jsonb 이고 비어도 쓰인다")
class ChatAuditLogRepositoryImplTest {

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
    private ChatAuditLogJpaRepository jpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final Instant NOW = Instant.parse("2026-11-06T05:00:00Z");

    private final UUID actorId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    /** 슬라이스는 Spring Data 인터페이스만 올리므로 어댑터는 직접 엮는다 — 앱에서는 {@code @Bean} 이다. */
    private ChatAuditLogRepository repository() {
        return new ChatAuditLogRepositoryImpl(jpaRepository);
    }

    private Map<String, Object> rowOf(UUID actor) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT action, actor_id, actor_type, target_id, target_type, created_at,
                       ip_address, detail, detail->>'word' AS word, detail->>'changed' AS changed
                  FROM live_schema.audit_logs
                 WHERE actor_id = ?
                """, actor);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /**
     * ⭐ {@code detail} 이 <b>진짜 jsonb</b>로 들어간다.
     *
     * <p>{@code detail->>'word'} 를 거는 것이 그 축을 재는 자리다 — 칸이 {@code text} 였다면 이
     * 연산자가 통하지 않고, 캐스트가 빠졌다면 쓰기 자체가
     * {@code column "detail" is of type jsonb but expression is of type character varying} 로 거부된다.
     */
    @Test
    @DisplayName("⭐ detail 이 jsonb 로 들어간다 — ->> 로 값을 꺼낼 수 있다")
    void theDetailLandsAsRealJsonb() {
        // given: 사전 제거 — 정본 행이 사라지므로 '무엇을' 이 이 칸에만 남는다
        ChatAuditEntry entry = new ChatAuditEntry(actorId, ChatRole.ADMIN,
                ChatAuditAction.CHAT_REMOVE_FILTER_WORD, null,
                Map.of("kind", "PROFANITY", "word", "시발", "changed", "true"), NOW);

        // when
        repository().append(entry);

        // then
        Map<String, Object> row = rowOf(actorId);
        assertThat(row.get("word")).isEqualTo("시발");
        assertThat(row.get("changed")).isEqualTo("true");
        assertThat(row.get("action")).isEqualTo("CHAT_REMOVE_FILTER_WORD");
        assertThat(row.get("actor_type")).isEqualTo("ADMIN");
        assertThat(row.get("target_type")).isEqualTo("FILTER_WORD");
        assertThat(row.get("target_id"))
                .as("UUID 로 가리킬 수 없는 행위에 대상 id 가 실렸다 — 그게 무엇을 가리키는지 아무도 모른다")
                .isNull();
    }

    /**
     * ⭐ {@code detail} 이 비어도 쓰인다.
     *
     * <p>이 칸을 안 쓰는 행위가 실제로 있다(종료 마커 해제 — 방은 {@code target_id} 가 가리키므로 더
     * 실을 것이 없다). 그때 어댑터는 {@code null} 을 넘기는데, 그 자리가
     * {@code CAST(:detail AS jsonb)} 라 Postgres 가 파라미터 타입을 정하지 못하면
     * {@code could not determine data type of parameter} 로 거부할 수 있다 — 캐스트를 쓰기로 한
     * 선택이 만든 위험이고, 그래서 비는 경로를 따로 잰다.
     */
    @Test
    @DisplayName("⭐ detail 이 비어도 행이 쓰인다 — CAST(NULL AS jsonb) 가 통하는가")
    void anEmptyDetailStillWrites() {
        // given: 종료 마커 해제 — detail 을 안 쓰는 행위
        ChatAuditEntry entry = new ChatAuditEntry(actorId, ChatRole.SELLER,
                ChatAuditAction.CHAT_CLEAR_ROOM_ENDED_MARKER, targetId, null, NOW);

        // when & then
        assertThatCode(() -> repository().append(entry)).doesNotThrowAnyException();

        Map<String, Object> row = rowOf(actorId);
        assertThat(row.get("detail")).isNull();
        assertThat(row.get("target_id")).isEqualTo(targetId);
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant())
                .as("시각이 주입된 시계가 아니라 DB 시계에서 왔다 — 이 테이블에는 기본값이 없다")
                .isEqualTo(NOW);
    }

    /**
     * {@code ip_address} 를 비운 채로 둔다. {@code X-Forwarded-For} 는 호출자가 바꿀 수 있어 증거가
     * 못 되고, 채우면 얻는 것 없이 PII 보존 의무가 생긴다 — 컬럼은 정본에 남겨 두고 쓰지 않는다.
     *
     * <p>이 칸을 쓰기 시작하면 이 단언이 먼저 깨지고, 그때 위 판단을 다시 꺼내 보게 된다.
     */
    @Test
    @DisplayName("ip_address 를 쓰지 않는다 — 바꿀 수 있는 값은 증거가 아니다")
    void theIpAddressStaysEmpty() {
        repository().append(new ChatAuditEntry(actorId, ChatRole.ADMIN,
                ChatAuditAction.CHAT_RELEASE_BAN, targetId,
                Map.of("banExisted", "true"), NOW));

        assertThat(rowOf(actorId).get("ip_address")).isNull();
    }
}
