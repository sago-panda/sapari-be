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

import com.sapari.live.infrastructure.persistence.entity.LiveSellerPermissionEntity;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("LiveSellerPermissionRepository — 행이 없으면 허용, 끄고 다시 켜면 그 값을 읽는다 (Postgres)")
class LiveSellerPermissionRepositoryImplTest {

    @Configuration
    @EntityScan(basePackageClasses = LiveSellerPermissionEntity.class)
    @EnableJpaRepositories(basePackageClasses = LiveSellerPermissionJpaRepository.class)
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

    @Autowired private LiveSellerPermissionJpaRepository jpaRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final Instant NOW = Instant.parse("2026-11-21T05:00:00Z");

    @Test
    @DisplayName("행이 없는 판매자는 허용")
    void noRow_isEnabled() {
        assertThat(new LiveSellerPermissionRepositoryImpl(jpaRepository).isLiveEnabled(UUID.randomUUID())).isTrue();
    }

    @Test
    @DisplayName("끄면 막히고, 다시 켜면 같은 행을 덮어 허용 — 판매자당 한 행")
    void disableThenEnable_overwritesOneRow() {
        LiveSellerPermissionRepositoryImpl repository = new LiveSellerPermissionRepositoryImpl(jpaRepository);
        UUID sellerId = UUID.randomUUID();

        repository.save(sellerId, false, NOW);
        assertThat(repository.isLiveEnabled(sellerId)).isFalse();

        repository.save(sellerId, true, NOW.plusSeconds(60));
        assertThat(repository.isLiveEnabled(sellerId)).isTrue();

        List<Instant> updatedAts = jdbcTemplate.queryForList(
                "SELECT updated_at FROM live_schema.live_seller_permission WHERE seller_id = ?",
                Timestamp.class, sellerId).stream().map(Timestamp::toInstant).toList();
        assertThat(updatedAts).containsExactly(NOW.plusSeconds(60));
    }
}
