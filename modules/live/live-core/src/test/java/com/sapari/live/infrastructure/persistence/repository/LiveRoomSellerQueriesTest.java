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
import java.util.Map;
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

import com.sapari.common.page.CursorPage;
import com.sapari.live.application.service.GetSellerRoomsService;
import com.sapari.live.command.GetSellerRoomsCommand;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;
import com.sapari.live.view.SellerLiveRoomSummary;

/**
 * 판매자 목록·개수 쿼리와 rtmp_url 저장을 <b>실제 Postgres + 운영 마이그레이션</b>으로 잰다(ddl-auto=validate).
 * 단위 테스트는 리포지토리를 목으로 두므로 JPQL 자체(키셋 조건·정렬·GROUP BY)는 여기서만 실행된다.
 *
 * <p>live-core 는 라이브러리라 부트 설정이 없다 — JPA 만 띄우는 최소 설정을 이 클래스 안에 둔다
 * (LiveKit·Redis 어댑터까지 스캔하지 않게).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("판매자 방 쿼리 — 키셋·탭 필터·개수·rtmp_url (Postgres)")
class LiveRoomSellerQueriesTest {

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
    private final UUID sellerId = UUID.randomUUID();

    private LiveRoomEntity persist(UUID seller, LiveRoomStatus status, Instant scheduledAt) {
        return jpa.save(LiveRoomEntity.builder()
                .sellerId(seller).title("방").sellerNickname("닉").liveStatus(status)
                .scheduledAt(scheduledAt)
                .endedAt(status == LiveRoomStatus.CANCELLED || status == LiveRoomStatus.ENDED ? T : null)
                .build());
    }

    private static final Set<Class<? extends LiveStatus>> ALL = Set.of(
            LiveStatus.Scheduled.class, LiveStatus.Ready.class, LiveStatus.Live.class,
            LiveStatus.Suspended.class, LiveStatus.Ended.class, LiveStatus.Cancelled.class);

    @Test
    @DisplayName("예정 시각 내림차순, 같은 시각은 id 내림차순 — 커서 다음 페이지가 겹치지도 빠지지도 않는다")
    void keysetPagingWithTies() {
        persist(sellerId, LiveRoomStatus.SCHEDULED, T.plusSeconds(120));
        persist(sellerId, LiveRoomStatus.SCHEDULED, T);
        persist(sellerId, LiveRoomStatus.CANCELLED, T);                    // 같은 시각 — id 로 갈린다
        persist(sellerId, LiveRoomStatus.ENDED, T.minusSeconds(120));
        persist(UUID.randomUUID(), LiveRoomStatus.SCHEDULED, T.plusSeconds(999)); // 남의 방
        em.flush();
        em.clear();

        List<LiveRoom> first = repository.findSellerRooms(sellerId, ALL, null, null, 2);
        LiveRoom last = first.get(1);
        List<LiveRoom> rest = repository.findSellerRooms(sellerId, ALL, last.scheduledAt(), last.id(), 10);

        List<LiveRoom> all = Stream.concat(first.stream(), rest.stream()).toList();
        assertThat(all).hasSize(4).allMatch(r -> r.sellerId().equals(sellerId));
        assertThat(all.stream().map(LiveRoom::id).distinct()).hasSize(4);
        assertThat(all).isSortedAccordingTo(Comparator.comparing(LiveRoom::scheduledAt)
                .thenComparing(LiveRoom::id).reversed());
    }

    @Test
    @DisplayName("서비스 커서로 넘겨도 소수 초 동점·같은 ms 행이 빠지지 않는다")
    void serviceCursorWithFractionalSeconds() {
        Instant at = Instant.parse("2026-11-10T09:00:00.123456Z");
        persist(sellerId, LiveRoomStatus.SCHEDULED, at);
        persist(sellerId, LiveRoomStatus.SCHEDULED, at);
        persist(sellerId, LiveRoomStatus.SCHEDULED, Instant.parse("2026-11-10T09:00:00.123200Z")); // 같은 ms, 더 이름
        em.flush();
        em.clear();
        GetSellerRoomsService service = new GetSellerRoomsService(repository);

        Set<UUID> seen = new java.util.HashSet<>();
        String cursor = null;
        do {
            CursorPage<SellerLiveRoomSummary> page = service.getRooms(new GetSellerRoomsCommand(sellerId, null, cursor, 1));
            page.content().forEach(r -> assertThat(seen.add(r.roomId())).as("중복 없음").isTrue());
            cursor = page.nextCursor();
        } while (cursor != null);

        assertThat(seen).hasSize(3);
    }

    @Test
    @DisplayName("상태 필터 — 취소 탭은 취소된 방만, 도메인 상태로 복원된다")
    void statusFilter() {
        persist(sellerId, LiveRoomStatus.SCHEDULED, T);
        persist(sellerId, LiveRoomStatus.CANCELLED, T.minusSeconds(60));
        em.flush();
        em.clear();

        List<LiveRoom> cancelled = repository.findSellerRooms(
                sellerId, Set.of(LiveStatus.Cancelled.class), null, null, 10);

        assertThat(cancelled).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(new LiveStatus.Cancelled(T.minusSeconds(60), T)));
    }

    @Test
    @DisplayName("상태별 개수 — 한 쿼리로, 남의 방은 세지 않는다")
    void countsByStatus() {
        persist(sellerId, LiveRoomStatus.SCHEDULED, T);
        persist(sellerId, LiveRoomStatus.SCHEDULED, T);
        persist(sellerId, LiveRoomStatus.READY, T);
        persist(sellerId, LiveRoomStatus.CANCELLED, T);
        persist(UUID.randomUUID(), LiveRoomStatus.SCHEDULED, T);
        em.flush();

        Map<Class<? extends LiveStatus>, Long> counts = repository.countSellerRoomsByStatus(sellerId);

        assertThat(counts).containsOnly(
                Map.entry(LiveStatus.Scheduled.class, 2L),
                Map.entry(LiveStatus.Ready.class, 1L),
                Map.entry(LiveStatus.Cancelled.class, 1L));
    }

    @Test
    @DisplayName("RTMP 배정이 rtmp_url 을 함께 저장하고, 두 번째 배정은 지지 않는다")
    void assignStoresRtmpUrl() {
        UUID roomId = persist(sellerId, LiveRoomStatus.SCHEDULED, T).getId();
        em.flush();

        assertThat(repository.assignRtmpIngressIfAbsent(roomId, sellerId, "ing-1", "rtmp://ingress/live", T)).isTrue();
        assertThat(repository.assignRtmpIngressIfAbsent(roomId, sellerId, "ing-2", "rtmp://other", T)).isFalse();

        LiveRoom room = repository.findById(roomId).orElseThrow();
        assertThat(room.rtmpUrl()).isEqualTo("rtmp://ingress/live");
    }
}
