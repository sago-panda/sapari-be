package com.sapari.live.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.live.domain.model.RecentView;
import com.sapari.live.infrastructure.persistence.entity.LiveRecentViewEntity;

/**
 * 최근 본 기록의 upsert·키셋 쿼리를 <b>실제 Postgres + 운영 마이그레이션</b>으로 잰다(ddl-auto=validate).
 * {@code ON CONFLICT … WHERE}(1분 넘게 늦을 때만 덮기)와 키셋 경계는 목으로는 검증되지 않는다. 구성은 {@code LiveRoomSellerQueriesTest} 와 같다.

 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("최근 본 기록 쿼리 — upsert 한 행·단조 시각·키셋 (Postgres)")
class RecentViewQueriesTest {

    @Configuration
    @EntityScan(basePackageClasses = LiveRecentViewEntity.class)
    @EnableJpaRepositories(basePackageClasses = LiveRecentViewJpaRepository.class)
    static class Wiring {
        @Bean
        RecentViewRepositoryImpl recentViewRepository(LiveRecentViewJpaRepository jpa) {
            return new RecentViewRepositoryImpl(jpa);
        }
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

    @Autowired private RecentViewRepositoryImpl repository;
    @Autowired private LiveRecentViewJpaRepository jpa;
    @Autowired private TestEntityManager entityManager;

    private static final Instant T = Instant.parse("2026-11-10T09:00:00.123456Z");
    private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");

    private final UUID userId = UUID.randomUUID();

    private List<RecentView> all(UUID user) {
        return repository.findRecent(user, FAR_PAST, null, null, 100);
    }

    @Test
    @DisplayName("같은 (회원, 방) 을 두 번 쓰면 한 행이고 시각은 늦은 쪽")
    void upsertKeepsOneRow() {
        UUID roomId = UUID.randomUUID();
        repository.upsert(new RecentView(userId, roomId, T));
        repository.upsert(new RecentView(userId, roomId, T.plusSeconds(120)));

        assertThat(all(userId)).containsExactly(new RecentView(userId, roomId, T.plusSeconds(120)));
    }

    @Test
    @DisplayName("1분 안의 재입장은 시각을 유지하고, 1분을 넘으면 덮는다")
    void reentryWithinOneMinuteKeepsTime() {
        UUID roomId = UUID.randomUUID();
        repository.upsert(new RecentView(userId, roomId, T));

        repository.upsert(new RecentView(userId, roomId, T.plusSeconds(30)));
        repository.upsert(new RecentView(userId, roomId, T.plusSeconds(60)));                       // 정확히 1분 — 유지
        assertThat(all(userId)).containsExactly(new RecentView(userId, roomId, T));
        // 위 조회가 엔티티를 영속성 컨텍스트에 올렸다 — 네이티브 upsert 는 그것을 갱신하지 않으므로 다음 조회 전에 비운다.
        entityManager.clear();

        Instant justOver = T.plusSeconds(60).plus(1, ChronoUnit.MICROS);
        repository.upsert(new RecentView(userId, roomId, justOver));
        assertThat(all(userId)).containsExactly(new RecentView(userId, roomId, justOver));
    }

    @Test
    @DisplayName("늦은 쓰기가 먼저 도착해도 시각이 되돌아가지 않는다")
    void upsertNeverMovesBackwards() {
        UUID roomId = UUID.randomUUID();
        repository.upsert(new RecentView(userId, roomId, T.plusSeconds(60)));
        repository.upsert(new RecentView(userId, roomId, T));

        assertThat(all(userId)).containsExactly(new RecentView(userId, roomId, T.plusSeconds(60)));
    }

    @Test
    @DisplayName("같은 시각(µs 까지) 다섯 행을 2개씩 끝까지 넘겨도 누락·중복이 없고 roomId 내림차순")
    void keysetWithIdenticalTimestamps() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(new RecentView(userId, UUID.randomUUID(), T));
        }

        List<RecentView> seen = new ArrayList<>();
        List<RecentView> page = repository.findRecent(userId, FAR_PAST, null, null, 2);
        while (!page.isEmpty()) {
            seen.addAll(page);
            RecentView last = page.get(page.size() - 1);
            page = repository.findRecent(userId, FAR_PAST, last.viewedAt(), last.roomId(), 2);
        }

        assertThat(seen).hasSize(5);
        assertThat(seen.stream().map(RecentView::roomId).distinct()).hasSize(5);
        // Postgres 는 uuid 를 부호 없는 바이트 순으로 정렬한다 — 소문자 16진 문자열 순과 같고, 부호 있는 long 을 비교하는
        // UUID.compareTo 와는 다르다(첫 비트가 1인 id 에서 갈린다).
        assertThat(seen).isSortedAccordingTo(
                Comparator.comparing((RecentView v) -> v.roomId().toString()).reversed());
    }

    @Test
    @DisplayName("같은 ms 안의 다른 µs 는 µs 로 정렬되고 커서 경계에서 빠지지 않는다")
    void microsecondPrecision() {
        UUID later = UUID.randomUUID();
        UUID earlier = UUID.randomUUID();
        repository.upsert(new RecentView(userId, later, T));                                // .123456
        repository.upsert(new RecentView(userId, earlier, T.minus(200, ChronoUnit.MICROS))); // .123256 — 같은 ms

        List<RecentView> first = repository.findRecent(userId, FAR_PAST, null, null, 1);
        List<RecentView> next = repository.findRecent(userId, FAR_PAST, first.get(0).viewedAt(), first.get(0).roomId(), 1);

        assertThat(first).extracting(RecentView::roomId).containsExactly(later);
        assertThat(next).extracting(RecentView::roomId).containsExactly(earlier);
    }

    @Test
    @DisplayName("since 와 같은 시각은 포함하고 1µs 이전은 뺀다")
    void sinceBoundary() {
        UUID atSince = UUID.randomUUID();
        UUID beforeSince = UUID.randomUUID();
        repository.upsert(new RecentView(userId, atSince, T));
        repository.upsert(new RecentView(userId, beforeSince, T.minus(1, ChronoUnit.MICROS)));

        assertThat(repository.findRecent(userId, T, null, null, 10))
                .extracting(RecentView::roomId).containsExactly(atSince);
        assertThat(repository.findRecent(userId, T, T.plusSeconds(1), UUID.randomUUID(), 10))
                .extracting(RecentView::roomId).containsExactly(atSince);
    }

    @Test
    @DisplayName("남의 기록은 나오지 않는다")
    void onlyOwnRows() {
        UUID other = UUID.randomUUID();
        repository.upsert(new RecentView(other, UUID.randomUUID(), T));
        UUID mine = UUID.randomUUID();
        repository.upsert(new RecentView(userId, mine, T));

        assertThat(all(userId)).extracting(RecentView::roomId).containsExactly(mine);
        assertThat(jpa.count()).isGreaterThanOrEqualTo(2);
    }
}
