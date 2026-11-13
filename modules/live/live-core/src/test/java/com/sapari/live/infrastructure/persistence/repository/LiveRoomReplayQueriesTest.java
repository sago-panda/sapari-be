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
import com.sapari.live.application.service.GetReplaysService;
import com.sapari.live.command.GetReplaysQuery;
import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;
import com.sapari.live.view.ReplaySummaryView;

/**
 * 공개 다시보기 목록 쿼리를 <b>실제 Postgres + 운영 마이그레이션</b>으로 잰다 — 노출 조건(종료·녹화 완료·공개·
 * 아카이브)과 (ended_at DESC, id DESC) 키셋이 동점에서도 빠지거나 겹치지 않는지.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("다시보기 목록 쿼리 — 노출 조건·키셋 (Postgres)")
class LiveRoomReplayQueriesTest {

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

    private static final Instant T = Instant.parse("2026-11-12T09:00:00.123456Z");
    private static final String LIVE_URL = "https://cdn/live/index.m3u8";
    private static final String ARCHIVE = "https://cdn/live/playlist.m3u8";

    /** 종료 방 하나. vod 상태는 웹훅 경로(advanceVodStatus)로만 바꿀 수 있어 그 경로로 세팅한다. */
    private UUID ended(Instant endedAt, VodStatus vod, String archive) {
        String egressId = "eg-" + UUID.randomUUID();
        UUID id = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(endedAt.minusSeconds(7200)).startedAt(endedAt.minusSeconds(3600)).endedAt(endedAt)
                .sfuRoomId("sfu").egressId(egressId).hlsUrl(LIVE_URL).hlsArchiveUrl(archive)
                .build()).getId();
        em.flush();
        if (vod != VodStatus.NONE) {
            repository.advanceVodStatus(egressId, vod, vod == VodStatus.FAILED ? "x" : null);
        }
        return id;
    }

    private UUID replay(Instant endedAt) {
        return ended(endedAt, VodStatus.READY, ARCHIVE);
    }

    private UUID withStatus(LiveRoomStatus status) {
        String egressId = "eg-" + UUID.randomUUID();
        UUID id = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(status)
                .scheduledAt(T).startedAt(status == LiveRoomStatus.SCHEDULED || status == LiveRoomStatus.READY ? null : T)
                .endedAt(status == LiveRoomStatus.CANCELLED ? T : null)
                .sfuRoomId("sfu").egressId(egressId).hlsUrl(LIVE_URL).hlsArchiveUrl(ARCHIVE)
                .suspendedAt(status == LiveRoomStatus.SUSPENDED ? T : null)
                .suspendedReason(status == LiveRoomStatus.SUSPENDED ? "중단" : null)
                .build()).getId();
        em.flush();
        repository.advanceVodStatus(egressId, VodStatus.READY, null);
        return id;
    }

    private List<UUID> walk(int size) {
        GetReplaysService service = new GetReplaysService(repository);
        List<UUID> seen = new java.util.ArrayList<>();
        String cursor = null;
        do {
            CursorPage<ReplaySummaryView> page = service.getReplays(new GetReplaysQuery(cursor, size));
            page.content().forEach(v -> seen.add(v.roomId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        return seen;
    }

    @Test
    @DisplayName("종료·녹화 완료·공개·아카이브 있음만, 종료 시각 내림차순으로 끝까지 한 번씩")
    void onlyPublicReadyReplays() {
        UUID newest = replay(T.plusSeconds(120));
        UUID middle = replay(T.plusSeconds(60));
        UUID oldest = replay(T);

        // 제외 대상
        ended(T.plusSeconds(30), VodStatus.NONE, ARCHIVE);
        ended(T.plusSeconds(30), VodStatus.PROCESSING, ARCHIVE);
        ended(T.plusSeconds(30), VodStatus.FAILED, ARCHIVE);
        ended(T.plusSeconds(30), VodStatus.READY, null);
        ended(T.plusSeconds(30), VodStatus.READY, "  ");
        ended(T.plusSeconds(30), VodStatus.READY, LIVE_URL); // 레거시 복사(hls_archive_url = hls_url)
        UUID privateRoom = replay(T.plusSeconds(30));
        em.createNativeQuery("UPDATE live_schema.live_rooms SET is_vod_public = false WHERE id = :id")
                .setParameter("id", privateRoom).executeUpdate();
        for (LiveRoomStatus s : List.of(LiveRoomStatus.SCHEDULED, LiveRoomStatus.READY, LiveRoomStatus.LIVE,
                LiveRoomStatus.SUSPENDED, LiveRoomStatus.CANCELLED)) {
            withStatus(s);
        }
        em.clear();

        assertThat(walk(2)).containsExactly(newest, middle, oldest);
    }

    @Test
    @DisplayName("마이크로초까지 같은 종료 시각이 페이지 경계에 걸려도 누락·중복이 없다")
    void tiesAcrossPageBoundary() {
        UUID a = replay(T);
        UUID b = replay(T);
        UUID c = replay(T);
        UUID older = replay(T.minusNanos(1_000)); // 1µs 이전
        em.clear();

        List<UUID> seen = walk(2);

        // 동점 안의 순서는 Postgres uuid 비교(바이트 무부호)라 Java UUID.compareTo 와 다르다 — 집합으로 본다.
        assertThat(seen).hasSize(4).doesNotHaveDuplicates();
        assertThat(seen.subList(0, 3)).containsExactlyInAnyOrder(a, b, c);
        assertThat(seen.get(3)).isEqualTo(older);
    }
}
