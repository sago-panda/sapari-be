package com.sapari.liveapp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.service.GoLiveByRtmpService;
import com.sapari.live.infrastructure.media.LiveKitMediaManager;
import com.sapari.liveapp.support.LiveAppContextFixture;

/**
 * RTMP go-live 경합 — <b>Ready 방에 승격 요청 둘이 동시에 와도 녹화는 한 번만 시작되는가</b>.
 *
 * <p>시나리오는 웹훅({@code ingress_started})과 Ready 고착 배치의 승격 분기가 같은 방에 동시에 닿는
 * 경우다. 둘 다 {@link GoLiveByRtmpService#goLiveByRtmp} 를 부르고, 둘 다 {@code Ready} 를 읽으면 각자
 * {@code startHlsEgress} 를 호출한다. LiveKit 재전송으로 같은 웹훅이 겹치는 경우도 같은 모양이다.
 *
 * <p>판매자 시작({@code StartLiveService})과 웹훅의 조합은 여기서 다루지 않는다 — 판매자 시작은
 * {@code Scheduled} 에서만, 웹훅 승격은 {@code Ready} 에서만 진행하므로 두 경로가 같은 상태를 읽고
 * 둘 다 녹화를 켜는 순서가 없다.
 *
 * <p><b>진짜 Postgres 를 띄우는 이유</b>: 지키는 것이 행 잠금({@code findByIdForUpdate}) 자체다. 목으로는
 * 잠금이 동작하는지 볼 수 없다. LiveKit 만 가짜로 바꾸고, {@code startHlsEgress} 안에서 잠깐 멈춰
 * 실제 LiveKit 호출 시간 동안 두 트랜잭션이 겹치는 구간을 만든다.
 */
@Tag("docker")
@SpringBootTest(properties = "spring.jpa.show-sql=true")
@Testcontainers
@DisplayName("RTMP go-live — 동시 승격 요청에도 녹화는 한 번만 시작된다")
class RtmpGoLiveRaceTest {

    private static final String INGRESS_ID = "IN_test";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    /**
     * 스키마는 운영 마이그레이션 파일로 세운다. {@code spring.flyway.*} 프로퍼티만으로는 이 컨텍스트에서
     * 마이그레이션이 돌지 않아(실측: {@code live_rooms} 없음) 여기서 직접 적용한다.
     */
    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas("live_schema")
                .createSchemas(true)
                .locations(LiveAppContextFixture.migrationLocation())
                .load()
                .migrate();
    }

    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        LiveAppContextFixture.register(registry);
    }

    /** 계측 데코레이터({@code MeteredLiveMediaManager})의 원본만 바꾼다 — 서비스는 데코레이터를 거쳐 여기로 온다. */
    @MockitoBean
    LiveKitMediaManager liveKit;

    @Autowired
    GoLiveByRtmpService goLiveByRtmpService;

    @Autowired
    JdbcTemplate jdbc;

    private final AtomicInteger egressStarts = new AtomicInteger();

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM live_schema.live_rooms");
    }

    @Test
    @DisplayName("웹훅과 배치 승격이 같은 Ready 방에 동시에 닿아도 startHlsEgress 는 한 번, 방은 Live")
    void concurrentPromotionsStartEgressOnce() throws Exception {
        UUID roomId = readyRtmpRoom();
        given(liveKit.startHlsEgress(eq(roomId))).willAnswer(invocation -> {
            int n = egressStarts.incrementAndGet();
            Thread.sleep(200); // 실제 LiveKit 호출 동안 다른 요청이 끼어들 틈
            return new HlsEgressResult("EG_" + n,
                    "https://cdn.example.test/hls/" + n + "/index.m3u8",
                    "https://cdn.example.test/hls/" + n + "/playlist.m3u8");
        });

        runConcurrently(
                () -> goLiveByRtmpService.goLiveByRtmp(roomId, INGRESS_ID, PromotionTrigger.WEBHOOK),
                () -> goLiveByRtmpService.goLiveByRtmp(roomId, INGRESS_ID, PromotionTrigger.RECONCILE));

        assertThat(egressStarts.get())
                .as("늦게 잠금을 얻은 쪽은 이미 Live 인 방을 읽고 아무것도 하지 않아야 한다")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM live_schema.live_rooms WHERE id = ?", String.class, roomId))
                .isEqualTo("LIVE");
    }

    /** 판매자가 시작을 눌러 Ready 가 됐고, 방이 인정한 ingress 가 배정된 RTMP 방. */
    private UUID readyRtmpRoom() {
        UUID roomId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO live_schema.live_rooms
                    (id, seller_id, title, seller_nickname, status, stream_type, sfu_room_id, ingress_id,
                     scheduled_at, created_at, updated_at)
                VALUES (?, ?, 'race', 'seller', 'READY', 'RTMP', ?, ?, ?, ?, ?)
                """, roomId, UUID.randomUUID(), roomId.toString(), INGRESS_ID, now, now, now);
        return roomId;
    }

    private static void runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                go.await();
                first.run();
                return null;
            });
            Future<?> b = pool.submit(() -> {
                go.await();
                second.run();
                return null;
            });
            go.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
