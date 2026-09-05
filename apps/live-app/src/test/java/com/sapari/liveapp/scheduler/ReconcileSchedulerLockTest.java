package com.sapari.liveapp.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.EgressSummary;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.IngressSummary;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.OrphanMediaReconcilePolicy;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.RoomSummary;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.application.service.ReconcileOrphanMediaService;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.ReconcileOrphanMediaUseCase;
import com.sapari.liveapp.config.ReconcileLockConfig;
import com.sapari.liveapp.config.ShedLockTableGuard;

/**
 * 정리 스케줄러 분산 락 — <b>레플리카 2대에서 외부 정리가 정확히 한 번만 나가는가</b>.
 *
 * <p>세 잡 중 {@code orphan-media} 로 검증한다. 나머지 둘은 행 잠금 + 상태 가드를 지나 커밋한 쪽만
 * {@code PostCommitMediaCleanup} 으로 외부 정리를 등록하므로 락이 없어도 LiveKit 호출은 한 번이지만,
 * 이 잡은 "전수 조회 → 판정 → 삭제"가 전부라 DB 관문이 아예 없다. 락이 지켜야 하는 자리가 여기다.
 *
 * <p><b>진짜 Postgres 를 띄우는 이유</b>: 검증 대상이 "인스턴스 2개가 같은 락 저장소를 보는 상황"
 * 자체다. 인메모리 DB 로 바꾸면 운영과 다른 프로바이더·다른 DDL 을 시험하게 되어 검증이 사라진다.
 * 테이블도 테스트용 DDL 이 아니라 <b>운영에 나갈 마이그레이션 파일을 그대로</b> 적용한다 — 그래야
 * 마이그레이션이 틀렸을 때 여기서 걸린다.
 *
 * <p>인스턴스는 {@link AnnotationConfigApplicationContext} 2개다. 컨텍스트가 곧 파드이고, 둘은
 * 같은 DB 와 같은 LiveKit(아래 {@link CountingMediaManager} 단일 인스턴스)을 본다.
 *
 * <p>{@code @Tag("docker")} — CI 빌드 잡에는 Docker 데몬이 없어 제외된다(루트 build.gradle).
 * 즉 <b>락 회귀는 CI 가 잡아주지 않는다</b>. live-app 스케줄러나 락 설정을 건드렸으면 로컬에서
 * 이 테스트를 직접 돌릴 것.
 */
@Tag("docker")
class ReconcileSchedulerLockTest {

    private static final String LOCK_NAME = "live-reconcile-orphan-media";

    /** 회차 판정 기준 시각. egress 는 이보다 grace(15m) 이상 오래돼야 회수 대상이 된다. */
    private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
    private static final Instant OLD = NOW.minus(Duration.ofHours(1));

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private final UUID roomId = UUID.randomUUID();

    @BeforeAll
    static void startDatabase() throws IOException {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS live_schema");
        jdbc.execute(shedlockMigrationSql());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearLocks() {
        jdbc.execute("DELETE FROM " + ReconcileLockConfig.LOCK_TABLE);
    }

    /**
     * 운영에 나갈 마이그레이션 파일을 읽어 그대로 적용한다. 여기에 DDL 을 복사해 두면 파일이 틀려도
     * 테스트는 통과해, 이 테스트가 지켜야 할 것 하나를 놓친다.
     */
    private static String shedlockMigrationSql() throws IOException {
        Path repoRoot = Path.of("").toAbsolutePath();
        while (repoRoot != null && !Files.exists(repoRoot.resolve("settings.gradle"))) {
            repoRoot = repoRoot.getParent();
        }
        if (repoRoot == null) {
            throw new IllegalStateException("리포지토리 루트(settings.gradle)를 찾지 못했습니다");
        }
        Path migrations = repoRoot.resolve("db/migration/live");
        try (Stream<Path> files = Files.list(migrations)) {
            Path sql = files.filter(p -> p.getFileName().toString().endsWith("__shedlock.sql"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("shedlock 마이그레이션을 찾지 못했습니다: " + migrations));
            return Files.readString(sql);
        }
    }

    @Test
    @DisplayName("부팅 가드의 쓰기 프로브가 마이그레이션이 만든 테이블에 실제로 통한다")
    void bootGuardWritesToTheMigratedTable() {
        // ShedLockTableGuardTest 는 실패 방향만 본다(JdbcTemplate 모킹). upsert 문법과 네 컬럼이
        // 진짜 스키마와 맞는지는 여기서만 확인된다 — 어긋나면 마이그레이션이 맞아도 부팅이 막힌다.
        assertThatCode(() -> new ShedLockTableGuard(jdbc)).doesNotThrowAnyException();

        // 두 번째 부팅은 ON CONFLICT 갈래로 간다. INSERT 만 되고 UPDATE 권한이 없는 계정을
        // 첫 부팅이 통과시키는 일이 없도록, 양쪽 경로를 모두 지난다.
        assertThatCode(() -> new ShedLockTableGuard(jdbc)).doesNotThrowAnyException();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + ReconcileLockConfig.LOCK_TABLE + " WHERE name = ?",
                Integer.class, ShedLockTableGuard.BOOT_CHECK))
                .as("예약 행은 하나만 남는다 — 부팅마다 쌓이면 안 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("두 인스턴스가 같은 회차를 동시에 돌아도 LiveKit 정리는 정확히 한 번 나간다")
    void concurrentInstancesCleanUpExactlyOnce() throws Exception {
        CountingMediaManager livekit = new CountingMediaManager();

        runConcurrently(livekit, new CountingMetrics(), true);

        assertThat(livekit.stopHlsEgressCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("락이 없으면 정확히 두 번 나간다 — 위 단언이 실제로 락을 보고 있다는 증거")
    void withoutLockTheSameCleanupRunsTwice() throws Exception {
        CountingMediaManager livekit = new CountingMediaManager();

        runConcurrently(livekit, new CountingMetrics(), false);

        assertThat(livekit.stopHlsEgressCalls()).isEqualTo(2);
    }

    @Test
    @DisplayName("락을 못 잡은 인스턴스는 조용히 넘어간다 — 예외도, 회차 기록도 남기지 않는다")
    void loserSkipsSilently() throws Exception {
        CountingMediaManager livekit = new CountingMediaManager();
        CountingMetrics metrics = new CountingMetrics();

        // 던지면 runConcurrently 안의 Future.get 에서 ExecutionException 으로 터진다.
        runConcurrently(livekit, metrics, true);

        // 회차를 돈 쪽만 기록이 남는다. 진 쪽이 aborted/failed 로 세면 "설정을 봐라"·"예외를 쫓아라"
        // 라는 기존 신호와 섞여, 락이 정상 동작한 회차가 장애로 읽힌다.
        assertThat(livekit.roundsEntered()).isEqualTo(1);
        assertThat(metrics.completedRounds()).isEqualTo(1);
        assertThat(metrics.failedRounds()).isZero();
    }

    @Test
    @DisplayName("락 보유 인스턴스가 죽어도 유지 시간이 지나면 다른 인스턴스가 인계한다")
    void anotherInstanceTakesOverAfterLeaseExpires() throws Exception {
        CountingMediaManager livekit = new CountingMediaManager();
        try (AnnotationConfigApplicationContext survivor = instance(livekit, new CountingMetrics(), true,
                Map.of("live.reconcile.lock-at-least-for", "PT0S"))) {

            OrphanMediaScheduler scheduler = survivor.getBean(OrphanMediaScheduler.class);

            // 죽은 인스턴스가 놓고 간 락 — 해제되지 않은 채 만료만 기다린다.
            // 컨텍스트를 <b>먼저</b> 띄우고 심는다. 순서를 바꾸면 5초 리스가 컨텍스트 refresh(프록시
            // 생성 포함) 동안 만료될 수 있어, 느린 러너에서 첫 단언이 이유 없이 깨진다.
            jdbc.update("INSERT INTO " + ReconcileLockConfig.LOCK_TABLE + "(name, lock_until, locked_at, locked_by) "
                    + "VALUES (?, timezone('utc', CURRENT_TIMESTAMP) + interval '5 seconds', "
                    + "timezone('utc', CURRENT_TIMESTAMP), 'dead-instance')", LOCK_NAME);

            scheduler.run();
            assertThat(livekit.stopHlsEgressCalls())
                    .as("유예 안에는 인계하지 않는다 — 죽었는지 느린지 구분할 수 없다")
                    .isZero();

            Thread.sleep(6_000); // 리스 5s + 여유. 붐비는 러너에서 DB 왕복·프록시 경유가 끼어도 견딘다

            scheduler.run();
            assertThat(livekit.stopHlsEgressCalls())
                    .as("유예가 지나면 살아 있는 인스턴스가 이어받는다")
                    .isEqualTo(1);
        }
    }

    /** 인스턴스 2개를 만들어 같은 잡을 동시에 진입시킨다. */
    private void runConcurrently(CountingMediaManager livekit, CountingMetrics metrics, boolean locked)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (AnnotationConfigApplicationContext a = instance(livekit, metrics, locked, Map.of());
             AnnotationConfigApplicationContext b = instance(livekit, metrics, locked, Map.of())) {

            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> results = Stream.of(a, b)
                    .map(context -> context.getBean(OrphanMediaScheduler.class))
                    .<Future<?>>map(scheduler -> pool.submit(() -> {
                        awaitQuietly(start);
                        scheduler.run();
                    }))
                    .toList();

            start.countDown();
            for (Future<?> result : results) {
                // 잡이 던졌으면 여기서 ExecutionException 으로 드러난다 — 진 쪽은 조용해야 한다.
                result.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 파드 하나. {@code locked=false} 는 이 변경 이전 상태다 — {@code @SchedulerLock} 은 그대로 붙어
     * 있지만 {@link ReconcileLockConfig} 가 없어 프록시가 만들어지지 않으므로 <b>아무 신호 없이</b>
     * 무시된다. 그 조용함이 이 티켓의 출발점이라, 테스트에서도 같은 방식으로 재현한다.
     */
    private AnnotationConfigApplicationContext instance(
            CountingMediaManager livekit, CountingMetrics metrics, boolean locked, Map<String, Object> properties) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties));
        context.registerBean(PropertySourcesPlaceholderConfigurer.class);
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(ReconcileOrphanMediaUseCase.class, () -> reconcileService(livekit, metrics));
        if (locked) {
            context.register(ReconcileLockConfig.class);
        }
        context.register(OrphanMediaScheduler.class);
        context.refresh();
        return context;
    }

    private ReconcileOrphanMediaService reconcileService(CountingMediaManager livekit, CountingMetrics metrics) {
        return new ReconcileOrphanMediaService(
                livekit,
                new SingleRoomRepository(endedRoom()),
                new OrphanMediaReconcilePolicy(Duration.ofMinutes(15)),
                new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)),
                metrics);
    }

    /** DB 는 끝났다고 하는데 LiveKit 에는 egress 가 살아 있는 방 — 회수 대상 1건. */
    private LiveRoom endedRoom() {
        return LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Ended(OLD, OLD, null))
                .streamType(new LiveStreamType.WebRtc())
                .updatedAt(OLD)
                .build();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * LiveKit 한 대. 두 인스턴스가 <b>같은 객체</b>를 보므로 호출 수가 곧 외부로 나간 요청 수다.
     *
     * <p>전수 조회에 지연을 넣는다 — 락이 없을 때 두 회차가 확실히 겹치게 만들어, 비교 테스트가
     * 타이밍에 따라 1이 나오는 일을 없앤다.
     */
    private final class CountingMediaManager implements LiveMediaManager {

        private final AtomicInteger stopCalls = new AtomicInteger();
        private final AtomicInteger rounds = new AtomicInteger();

        int stopHlsEgressCalls() {
            return stopCalls.get();
        }

        int roundsEntered() {
            return rounds.get();
        }

        @Override
        public List<EgressSummary> listAllEgress() {
            rounds.incrementAndGet();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return List.of(new EgressSummary("eg-1", roomId.toString(), true, OLD));
        }

        @Override
        public void stopHlsEgress(UUID roomId) {
            stopCalls.incrementAndGet();
        }

        @Override
        public List<IngressSummary> listAllIngress() {
            return List.of();
        }

        @Override
        public List<RoomSummary> listAllRooms() {
            return List.of();
        }

        @Override
        public SfuRoomResult createRoom(UUID roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String issueSellerToken(UUID roomId, UUID sellerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IngressResult createIngress(UUID roomId, UUID sellerId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> publishingIngressIdsOrEmpty(UUID roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<IngressSummary> listRoomIngress(UUID roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HlsEgressResult startHlsEgress(UUID roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteIngress(UUID roomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteIngress(UUID roomId, String ingressId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void closeRoom(String sfuRoomId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSfuUrl() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 회차 지표만 세는 페이크. {@code LiveMetrics.NOOP} 를 쓰면 "진 쪽은 회차를 기록하지 않는다"를
     * 이름으로만 걸고 실제로는 확인하지 못한다 — 나중에 락 예외를 스케줄러 안쪽으로 옮기는 식으로
     * 배치를 바꾸면 조용히 회귀한다.
     */
    private static final class CountingMetrics implements LiveMetrics {

        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();

        int completedRounds() {
            return completed.get();
        }

        int failedRounds() {
            return failed.get();
        }

        @Override
        public void reconcileRoundCompleted(ReconcileJob job, Duration elapsed) {
            completed.incrementAndGet();
        }

        @Override
        public void reconcileRoundFailed(ReconcileJob job) {
            failed.incrementAndGet();
        }

        /** 나머지는 이 테스트의 관심사가 아니다 — 잡이 부르므로 구현만 비워 둔다. */
        @Override
        public void liveKitActiveEgressRooms(int rooms) {
        }

        @Override
        public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) {
        }

        @Override
        public void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason) {
        }

        @Override
        public void roomTransitioned(LiveStatus from, LiveStatus to) {
        }

        @Override
        public void rtmpPromoted(PromotionTrigger trigger) {
        }
    }

    /** 이 방 하나만 아는 저장소. 고아 미디어 잡은 DB 를 읽기만 한다. */
    private record SingleRoomRepository(LiveRoom room) implements LiveRoomRepository {

        @Override
        public List<LiveRoom> findAllByIds(Set<UUID> ids) {
            return ids.contains(room.id()) ? List.of(room) : List.of();
        }

        @Override
        public LiveRoom save(LiveRoom liveRoom) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LiveRoom> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LiveRoom> findByIdAndSellerId(UUID id, UUID hostId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LiveRoom> findByIdForUpdate(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LiveRoom> findByIdAndSellerIdForUpdate(UUID id, UUID hostId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> findExpiredReadyRoomIds(Instant threshold, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean assignRtmpIngressIfAbsent(UUID roomId, UUID sellerId, String ingressId, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<UUID> findStaleLiveRoomIds(Instant threshold, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long countLiveRooms() {
            throw new UnsupportedOperationException();
        }
    }
}
