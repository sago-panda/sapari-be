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

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;
import com.sapari.live.infrastructure.persistence.mapper.LiveRoomMapper;

/**
 * vod_status 에 닿는 경로 세 가지를 <b>실제 Postgres</b> 로 잰다(백필 마이그레이션 SQL 도 여기서 돌린다).
 * <ul>
 *   <li>웹훅의 조건부 UPDATE({@code advanceVodStatus}) — 앞으로만 가고, 중복·역순·다른 egress 는 0행.</li>
 *   <li>복구의 조건부 UPDATE({@code recoverStuckReplays}) — 유예가 지난 아카이브 있는 NONE/PROCESSING 종료 방만 READY.</li>
 *   <li>엔티티 저장 경로 — Hibernate 는 변경된 필드가 하나라도 있으면 모든 컬럼을 UPDATE 하므로, 매핑이 vod 컬럼을
 *       쓰면 먼저 읽어 둔 낡은 값으로 웹훅 결과를 되돌린다. 저장이 이 컬럼을 건드리지 않음을 확인한다.</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("vod_status 갱신 — 조건부 UPDATE 와 엔티티 저장 (Postgres)")
class LiveRoomVodStatusUpdateTest {

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

    private static final Instant T = Instant.parse("2026-11-12T09:00:00Z");

    private UUID endedRoom(String egressId) {
        UUID id = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(T.minusSeconds(7200)).startedAt(T.minusSeconds(3600)).endedAt(T)
                .sfuRoomId("sfu").egressId(egressId).hlsUrl("https://cdn/live/index.m3u8")
                .hlsArchiveUrl("https://cdn/live/playlist.m3u8")
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private Object[] vodColumns(UUID id) {
        em.clear();
        return (Object[]) em.createNativeQuery(
                        "SELECT vod_status, vod_failed_reason, updated_at FROM live_schema.live_rooms WHERE id = :id")
                .setParameter("id", id).getSingleResult();
    }

    private static Instant toInstant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case java.time.OffsetDateTime o -> o.toInstant();
            case java.sql.Timestamp t -> t.toInstant();
            default -> throw new IllegalStateException("예상 밖 타입: " + value.getClass());
        };
    }

    private String vodStatus(UUID id) {
        return (String) vodColumns(id)[0];
    }

    @Test
    @DisplayName("새 방은 DB 기본값 NONE 으로 삽입된다")
    void insertDefaultsToNone() {
        assertThat(vodStatus(endedRoom("eg-1"))).isEqualTo("NONE");
    }

    @Test
    @DisplayName("NONE → PROCESSING → READY 로 앞으로 간다 — updated_at 은 건드리지 않는다(정리 잡의 유예 기준)")
    void forward() {
        UUID id = endedRoom("eg-1");
        Instant before = toInstant(vodColumns(id)[2]);

        assertThat(repository.advanceVodStatus("eg-1", VodStatus.PROCESSING, null)).isTrue();
        assertThat(vodStatus(id)).isEqualTo("PROCESSING");
        assertThat(repository.advanceVodStatus("eg-1", VodStatus.READY, null)).isTrue();

        Object[] row = vodColumns(id);
        assertThat(row[0]).isEqualTo("READY");
        assertThat(toInstant(row[2])).isEqualTo(before);
    }

    @Test
    @DisplayName("끝 상태 뒤의 늦은 시작·진행 알림은 0행 — 뒤로 가지 않는다")
    void lateProcessingAfterReady() {
        UUID id = endedRoom("eg-1");
        repository.advanceVodStatus("eg-1", VodStatus.READY, null);

        assertThat(repository.advanceVodStatus("eg-1", VodStatus.PROCESSING, null)).isFalse();
        assertThat(vodStatus(id)).isEqualTo("READY");
    }

    @Test
    @DisplayName("종료 알림이 먼저 오면 NONE 에서 바로 끝 상태가 된다")
    void endedBeforeStarted() {
        UUID id = endedRoom("eg-1");

        assertThat(repository.advanceVodStatus("eg-1", VodStatus.FAILED, "boom")).isTrue();
        assertThat(repository.advanceVodStatus("eg-1", VodStatus.PROCESSING, null)).isFalse();

        Object[] row = vodColumns(id);
        assertThat(row[0]).isEqualTo("FAILED");
        assertThat(row[1]).isEqualTo("boom");
    }

    @Test
    @DisplayName("같은 알림의 중복은 두 번째가 0행이다")
    void duplicate() {
        endedRoom("eg-1");

        assertThat(repository.advanceVodStatus("eg-1", VodStatus.READY, null)).isTrue();
        assertThat(repository.advanceVodStatus("eg-1", VodStatus.READY, null)).isFalse();
    }

    @Test
    @DisplayName("끝 상태끼리는 먼저 온 쪽이 이긴다 — READY 뒤 FAILED 는 사유도 남기지 않는다")
    void firstTerminalWins() {
        UUID id = endedRoom("eg-1");
        repository.advanceVodStatus("eg-1", VodStatus.READY, null);

        assertThat(repository.advanceVodStatus("eg-1", VodStatus.FAILED, "late failure")).isFalse();

        Object[] row = vodColumns(id);
        assertThat(row[0]).isEqualTo("READY");
        assertThat(row[1]).isNull();
    }

    @Test
    @DisplayName("방이 기록하지 않은 egress 의 알림은 어떤 방도 바꾸지 않는다")
    void unknownEgress() {
        UUID id = endedRoom("eg-1");

        assertThat(repository.advanceVodStatus("eg-other", VodStatus.READY, null)).isFalse();
        assertThat(repository.advanceVodStatus("eg-other", VodStatus.NONE, null)).isFalse();
        assertThat(vodStatus(id)).isEqualTo("NONE");
    }

    @Test
    @DisplayName("엔티티 저장 경로는 웹훅이 쓴 vod_status·사유를 덮지 않는다 — 웹훅 전에 읽어 둔 낡은 엔티티로 저장해도")
    void entitySaveDoesNotOverwriteVodColumns() {
        UUID id = endedRoom("eg-1");
        LiveRoom loaded = repository.findById(id).orElseThrow(); // 영속성 컨텍스트에 vod_status=NONE 엔티티가 남는다

        // 다른 트랜잭션의 웹훅 커밋을 흉내 낸다 — 이 컨텍스트의 관리 엔티티는 모른 채 DB 만 바뀐다.
        em.createNativeQuery("UPDATE live_schema.live_rooms SET vod_status = 'FAILED', vod_failed_reason = 'egress crashed'"
                + " WHERE id = :id").setParameter("id", id).executeUpdate();

        repository.save(loaded.toBuilder().title("새 제목").build()); // 같은 컨텍스트의 낡은 엔티티에 적용·flush
        em.flush();

        Object[] row = vodColumns(id);
        assertThat(row[0]).isEqualTo("FAILED");
        assertThat(row[1]).isEqualTo("egress crashed");
        assertThat(repository.findById(id).orElseThrow().title()).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("백필 마이그레이션 — 구 코드가 종료한 다시보기 있는 방만 READY 로, 다시 돌려도 같은 결과")
    void backfillMarksLegacyReplaysReady() throws Exception {
        UUID replay = endedRoom("eg-1");
        UUID noArchive = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(T).startedAt(T).endedAt(T).sfuRoomId("sfu").egressId("eg-2")
                .hlsUrl("https://cdn/live/index.m3u8").build()).getId();
        UUID legacyCopy = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(T).startedAt(T).endedAt(T).sfuRoomId("sfu").egressId("eg-3")
                .hlsUrl("https://cdn/live/index.m3u8").hlsArchiveUrl("https://cdn/live/index.m3u8").build()).getId();
        UUID live = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.LIVE)
                .scheduledAt(T).startedAt(T).sfuRoomId("sfu").egressId("eg-4")
                .hlsUrl("https://cdn/live/index.m3u8").hlsArchiveUrl("https://cdn/live/playlist.m3u8").build()).getId();
        UUID failed = endedRoom("eg-5");
        repository.advanceVodStatus("eg-5", VodStatus.FAILED, "boom");
        em.flush();

        runBackfill();
        runBackfill();

        assertThat(vodStatus(replay)).isEqualTo("READY");
        assertThat(vodStatus(noArchive)).isEqualTo("NONE");
        assertThat(vodStatus(legacyCopy)).isEqualTo("NONE");
        assertThat(vodStatus(live)).isEqualTo("NONE");
        assertThat(vodStatus(failed)).isEqualTo("FAILED");
    }

    private void runBackfill() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        String sql = Files.readString(root.resolve("db/migration/live/V202611121201__live_rooms_vod_status_backfill.sql"));
        em.createNativeQuery(sql).executeUpdate();
    }

    @Test
    @DisplayName("놓친 다시보기 복구 — 기준 시각 전에 끝난 아카이브 있는 NONE/PROCESSING 종료 방만 READY, 비공개도 포함")
    void recoverStuckReplays() {
        UUID none = endedRoom("eg-1");
        UUID processing = endedRoom("eg-2");
        UUID failed = endedRoom("eg-3");
        UUID privateRoom = endedRoom("eg-4");
        repository.advanceVodStatus("eg-2", VodStatus.PROCESSING, null);
        repository.advanceVodStatus("eg-3", VodStatus.FAILED, "boom");
        em.createNativeQuery("UPDATE live_schema.live_rooms SET is_vod_public = false WHERE id = :id")
                .setParameter("id", privateRoom).executeUpdate();
        UUID noArchive = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(T).startedAt(T).endedAt(T).sfuRoomId("sfu").egressId("eg-5")
                .hlsUrl("https://cdn/live/index.m3u8").build()).getId();
        UUID legacyCopy = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.ENDED)
                .scheduledAt(T).startedAt(T).endedAt(T).sfuRoomId("sfu").egressId("eg-6")
                .hlsUrl("https://cdn/live/index.m3u8").hlsArchiveUrl("https://cdn/live/index.m3u8").build()).getId();
        UUID cancelled = jpa.save(LiveRoomEntity.builder()
                .sellerId(UUID.randomUUID()).title("방").sellerNickname("닉").liveStatus(LiveRoomStatus.CANCELLED)
                .scheduledAt(T).endedAt(T).hlsArchiveUrl("https://cdn/live/playlist.m3u8").build()).getId();
        em.flush();

        Instant updatedBefore = toInstant(vodColumns(none)[2]);
        assertThat(repository.recoverStuckReplays(T)).as("기준 시각과 같은 종료는 아직 유예 안").isZero();
        assertThat(vodStatus(none)).isEqualTo("NONE");

        assertThat(repository.recoverStuckReplays(T.plusSeconds(1))).isEqualTo(3);

        assertThat(vodStatus(none)).isEqualTo("READY");
        assertThat(toInstant(vodColumns(none)[2])).as("updated_at 은 정리 잡의 유예 기준이라 건드리지 않는다")
                .isEqualTo(updatedBefore);
        assertThat(vodStatus(processing)).isEqualTo("READY");
        assertThat(vodStatus(privateRoom)).isEqualTo("READY");
        assertThat(vodStatus(failed)).isEqualTo("FAILED");
        assertThat(vodStatus(noArchive)).isEqualTo("NONE");
        assertThat(vodStatus(legacyCopy)).isEqualTo("NONE");
        assertThat(vodStatus(cancelled)).isEqualTo("NONE");
        assertThat(repository.recoverStuckReplays(T.plusSeconds(1))).as("다시 돌려도 같은 결과").isZero();
    }
}
