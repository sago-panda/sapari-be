package com.sapari.live.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.live.infrastructure.persistence.entity.LiveAuditLogEntity;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("LiveAuditLogRepository — 관리자 조치가 live_schema.audit_logs 에 한 줄로 남는다 (Postgres)")
class LiveAuditLogRepositoryImplTest {

    @Configuration
    @EntityScan(basePackageClasses = LiveAuditLogEntity.class)
    @EnableJpaRepositories(basePackageClasses = LiveAuditLogJpaRepository.class)
    static class Wiring {
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @BeforeAll
    static void applyRealSchema() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        List<Path> migrations;
        try (Stream<Path> files = Files.walk(root.resolve("db/migration/live"))) {
            migrations = files.filter(p -> p.toString().endsWith(".sql"))
                    .sorted(Comparator.comparingLong(p -> {
                        String name = p.getFileName().toString();
                        return Long.parseLong(name.substring(1, name.indexOf("__")));
                    }))
                    .toList();
        }
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement st = c.createStatement()) {
            for (Path m : migrations) {
                st.execute(Files.readString(m));
            }
        }
    }

    @Autowired private LiveAuditLogJpaRepository jpaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Instant NOW = Instant.parse("2026-11-20T05:00:00Z");

    @Test
    @DisplayName("관리자·방·사유·조치 시각이 그대로 남고, 사유는 detail jsonb 에서 꺼낼 수 있다")
    void appendAdminEnd_writesOneRow() {
        UUID adminId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();

        new LiveAuditLogRepositoryImpl(jpaRepository).appendAdminEnd(adminId, roomId, "음란물 \"송출\"", NOW);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT actor_id, actor_type, action, target_id, target_type, created_at,
                       detail->>'reason' AS reason
                  FROM live_schema.audit_logs
                 WHERE actor_id = ?
                """, adminId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_type")).isEqualTo("ADMIN");
        assertThat(row.get("action")).isEqualTo("LIVE_ADMIN_END");
        assertThat(row.get("target_id")).isEqualTo(roomId);
        assertThat(row.get("target_type")).isEqualTo("LIVE_ROOM");
        assertThat(row.get("reason")).isEqualTo("음란물 \"송출\"");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("판매자 권한 변경 — 관리자·판매자·조치 시각이 남고, 바꾼 값(boolean)과 사유는 detail jsonb 에 있다")
    void appendSellerLivePermission_writesOneRow() {
        UUID adminId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();

        new LiveAuditLogRepositoryImpl(jpaRepository)
                .appendSellerLivePermission(adminId, sellerId, false, "반복 \"위반\"", NOW);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT actor_type, action, target_id, target_type, created_at,
                       detail->'liveEnabled' AS live_enabled_json,
                       detail->>'reason' AS reason
                  FROM live_schema.audit_logs
                 WHERE actor_id = ?
                """, adminId);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("actor_type")).isEqualTo("ADMIN");
        assertThat(row.get("action")).isEqualTo("LIVE_ADMIN_SELLER_PERMISSION");
        assertThat(row.get("target_id")).isEqualTo(sellerId);
        assertThat(row.get("target_type")).isEqualTo("SELLER");
        assertThat(row.get("live_enabled_json").toString()).isEqualTo("false");
        assertThat(row.get("reason")).isEqualTo("반복 \"위반\"");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(NOW);
    }
}
