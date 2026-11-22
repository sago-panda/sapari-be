package com.sapari.live.infrastructure.persistence.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.AdminRoomFilter;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;

/**
 * 관리자 방 목록 쿼리(Specification)를 <b>실제 Postgres + 운영 마이그레이션</b>으로 잰다(ddl-auto=validate).
 * 필터 조합마다 목록과 개수가 같은 행을 보는지, 페이지 경계에서 행이 겹치거나 빠지지 않는지를 본다.
 * 설정 구성은 {@code LiveRoomSellerQueriesTest} 와 같다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("관리자 방 쿼리 — 필터 조합·개수·정렬·페이지 (Postgres)")
class LiveRoomAdminQueriesTest {

    @Configuration
    @EnableJpaAuditing
    @EntityScan(basePackageClasses = LiveRoomEntity.class)
    @EnableJpaRepositories(basePackageClasses = LiveRoomJpaRepository.class)
    static class Wiring {
        @Bean
        LiveRoomMapper liveRoomMapper() {
            return Mappers.getMapper(LiveRoomMapper.class);
        }

        @Bean
        LiveRoomRepositoryImpl liveRoomRepository(LiveRoomJpaRepository jpa, LiveRoomMapper mapper) {
            return new LiveRoomRepositoryImpl(jpa, mapper);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @DynamicPropertySource
    static void schemaValidation(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    /** 운영과 같은 live 마이그레이션 전부를 Flyway 순서(V 뒤 숫자)로. chat-core LiveSchema 와 같은 규칙. */
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

    @Autowired private LiveRoomRepositoryImpl repository;
    @Autowired private LiveRoomJpaRepository jpa;
    @Autowired private EntityManager em;

    private static final Instant T = Instant.parse("2026-11-10T09:00:00Z");
    private static final Set<Class<? extends LiveStatus>> ALL = Set.of(
            LiveStatus.Scheduled.class, LiveStatus.Ready.class, LiveStatus.Live.class,
            LiveStatus.Suspended.class, LiveStatus.Ended.class, LiveStatus.Cancelled.class);

    private final UUID sellerA = UUID.randomUUID();
    private final UUID sellerB = UUID.randomUUID();

    private UUID persist(UUID seller, LiveRoomStatus status, Instant scheduledAt, Instant startedAt) {
        boolean terminal = status == LiveRoomStatus.CANCELLED || status == LiveRoomStatus.ENDED;
        return jpa.save(LiveRoomEntity.builder()
                .sellerId(seller).title("방").sellerNickname("닉").liveStatus(status)
                .scheduledAt(scheduledAt).startedAt(startedAt)
                .endedAt(terminal ? scheduledAt.plusSeconds(600) : null)
                .build()).getId();
    }

    private UUID persist(UUID seller, LiveRoomStatus status, Instant scheduledAt) {
        return persist(seller, status, scheduledAt, null);
    }

    private List<UUID> ids(AdminRoomFilter filter) {
        return repository.findAdminRooms(filter, 0, 100).stream().map(LiveRoom::id).toList();
    }

    @Test
    @DisplayName("필터 조합 — 상태·판매자·기간 [from, to) 가 함께 걸리고, 개수가 목록과 같은 행을 센다")
    void filterCombinations() {
        UUID aScheduledIn = persist(sellerA, LiveRoomStatus.SCHEDULED, T);
        UUID aReadyIn = persist(sellerA, LiveRoomStatus.READY, T.plusSeconds(60));
        UUID aEndedIn = persist(sellerA, LiveRoomStatus.ENDED, T.plusSeconds(120), T.plusSeconds(120));
        UUID aScheduledAtTo = persist(sellerA, LiveRoomStatus.SCHEDULED, T.plusSeconds(3600)); // to 는 제외
        UUID aScheduledBefore = persist(sellerA, LiveRoomStatus.SCHEDULED, T.minusSeconds(1));
        UUID bScheduledIn = persist(sellerB, LiveRoomStatus.SCHEDULED, T.plusSeconds(30));
        em.flush();
        em.clear();

        Set<Class<? extends LiveStatus>> scheduledTab = Set.of(LiveStatus.Scheduled.class, LiveStatus.Ready.class);
        Instant from = T;
        Instant to = T.plusSeconds(3600);

        AdminRoomFilter all = new AdminRoomFilter(ALL, null, null, null);
        AdminRoomFilter byStatus = new AdminRoomFilter(scheduledTab, null, null, null);
        AdminRoomFilter bySeller = new AdminRoomFilter(ALL, sellerA, null, null);
        AdminRoomFilter byPeriod = new AdminRoomFilter(ALL, null, from, to);
        AdminRoomFilter combined = new AdminRoomFilter(scheduledTab, sellerA, from, to);

        assertThat(ids(all)).containsExactlyInAnyOrder(
                aScheduledIn, aReadyIn, aEndedIn, aScheduledAtTo, aScheduledBefore, bScheduledIn);
        assertThat(ids(byStatus)).containsExactlyInAnyOrder(
                aScheduledIn, aReadyIn, aScheduledAtTo, aScheduledBefore, bScheduledIn);
        assertThat(ids(bySeller)).containsExactlyInAnyOrder(
                aScheduledIn, aReadyIn, aEndedIn, aScheduledAtTo, aScheduledBefore);
        assertThat(ids(byPeriod)).containsExactlyInAnyOrder(aScheduledIn, aReadyIn, aEndedIn, bScheduledIn);
        assertThat(ids(combined)).containsExactlyInAnyOrder(aScheduledIn, aReadyIn);

        for (AdminRoomFilter f : List.of(all, byStatus, bySeller, byPeriod, combined)) {
            assertThat(repository.countAdminRooms(f)).as("개수 = 목록 %s", f).isEqualTo(ids(f).size());
        }
    }

    @Test
    @DisplayName("예정 시각 내림차순, 같은 시각은 id 내림차순 — 페이지를 넘겨도 겹치지도 빠지지도 않는다")
    void orderAndPagingWithTies() {
        for (int i = 0; i < 5; i++) {
            persist(sellerA, LiveRoomStatus.SCHEDULED, T);                 // 같은 시각 5건 — id 로 갈린다
        }
        persist(sellerB, LiveRoomStatus.SCHEDULED, T.plusSeconds(60));
        persist(sellerB, LiveRoomStatus.CANCELLED, T.minusSeconds(60));
        em.flush();
        em.clear();
        AdminRoomFilter all = new AdminRoomFilter(ALL, null, null, null);

        List<LiveRoom> rows = Stream.of(0, 1, 2, 3)
                .flatMap(page -> repository.findAdminRooms(all, page, 2).stream())
                .toList();

        assertThat(rows).hasSize(7);
        assertThat(rows.stream().map(LiveRoom::id).distinct()).hasSize(7);
        assertThat(rows).isSortedAccordingTo(Comparator.comparing(LiveRoom::scheduledAt)
                .thenComparing(LiveRoom::id).reversed());
        assertThat(repository.findAdminRooms(all, 4, 2)).isEmpty();
    }

    @Test
    @DisplayName("관리자 종료한 방은 CANCELLED 로 걸리고, 시작 시각이 도메인 상태로 복원된다")
    void adminEndedRoomIsCancelled() {
        persist(sellerA, LiveRoomStatus.CANCELLED, T, T.plusSeconds(10));
        persist(sellerA, LiveRoomStatus.ENDED, T, T.plusSeconds(10));
        em.flush();
        em.clear();

        List<LiveRoom> cancelled = repository.findAdminRooms(
                new AdminRoomFilter(Set.of(LiveStatus.Cancelled.class), null, null, null), 0, 10);

        assertThat(cancelled).singleElement().satisfies(r -> assertThat(r.status())
                .isEqualTo(new LiveStatus.Cancelled(T, T.plusSeconds(10), T.plusSeconds(600))));
    }
}
