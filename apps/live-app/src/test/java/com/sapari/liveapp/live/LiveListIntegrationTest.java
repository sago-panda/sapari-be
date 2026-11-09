package com.sapari.liveapp.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.sapari.liveapp.support.LiveAppContextFixture;
import com.sapari.liveapp.support.LiveSchemaFixture;

import com.jayway.jsonpath.JsonPath;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.service.GoLiveByRtmpService;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveProductRepository;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;
import com.sapari.live.port.EndLiveUseCase;
import com.sapari.live.port.EndStaleLiveUseCase;
import com.sapari.live.port.RefreshLiveListUseCase;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.StartLiveCommand;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.StartLiveUseCase;

/**
 * 공개 라이브 목록이 방송 상태를 따라가는지 — 실제 DB·Redis 위에서 본다(SPR-157).
 *
 * <p>LiveKit 만 목으로 바꾼다. 목록은 전이의 <b>커밋 후</b> 반영되므로, 전이를 유스케이스(트랜잭션 경계)로
 * 일으키고 HTTP 로 읽는다 — 저장소를 직접 부르면 커밋 후 경로를 건너뛴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("라이브 공개 목록 — 방송 상태와 함께 움직인다")
class LiveListIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.15");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static MongoDBContainer mongo = new MongoDBContainer("mongo:7.0");

    @DynamicPropertySource
    static void requiredConfig(DynamicPropertyRegistry registry) {
        LiveAppContextFixture.register(registry);
    }

    /** 컨테이너는 확장이 먼저 띄우고, 컨텍스트는 첫 테스트 인스턴스 때 뜬다 — 그 사이에 스키마를 세운다. */
    @BeforeAll
    static void createSchema() throws Exception {
        LiveSchemaFixture.apply(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @MockitoBean
    LiveMediaManager liveMediaManager;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    LiveRoomRepository liveRoomRepository;

    @Autowired
    StartLiveUseCase startLiveUseCase;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    GoLiveByRtmpService goLiveByRtmpService;

    @Autowired
    EndLiveUseCase endLiveUseCase;

    @Autowired
    EndStaleLiveUseCase endStaleLiveUseCase;

    @Autowired
    LiveRoomCacheRepository liveRoomCacheRepository;

    @Autowired
    RefreshLiveListUseCase refreshLiveListUseCase;

    @MockitoSpyBean
    LiveProductRepository liveProductRepository;

    private static final String INGRESS_ID = "ingress-1";

    @BeforeEach
    void setUp() {
        // 테스트 간 목록 캐시 격리 — 이 컨테이너는 이 클래스 전용이라 통째로 비워도 된다.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        // DB 도 비운다 — 재조정은 DB 의 Live 방 전부를 원본으로 삼으므로 앞 테스트의 방이 남으면 섞인다.
        jdbcTemplate.execute("TRUNCATE live_schema.live_rooms CASCADE");

        given(liveMediaManager.issueSellerToken(any(), any())).willReturn("seller-token");
        given(liveMediaManager.getSfuUrl()).willReturn("wss://sfu.example.test");
        given(liveMediaManager.startHlsEgress(any())).willAnswer(inv -> new HlsEgressResult(
                "egress-" + inv.getArgument(0),
                "https://cdn.example.test/" + inv.getArgument(0) + "/index.m3u8",
                "https://cdn.example.test/" + inv.getArgument(0) + "/playlist.m3u8"));
    }

    @Test
    @DisplayName("방송이 없으면 빈 목록이 성공 응답으로 온다")
    void emptyWhenNothingLive() throws Exception {
        mockMvc.perform(get("/api/v1/lives/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rooms.length()").value(0));
    }

    @Test
    @DisplayName("WebRTC 로 방송을 시작하면 목록에 그 방이 나타난다 — 제목·시작 시각과 함께")
    void startedWebRtcRoomAppearsInList() throws Exception {
        LiveRoom room = scheduledRoom("첫 방송");

        startBySeller(room);

        mockMvc.perform(get("/api/v1/lives/rooms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(room.id().toString()))
                .andExpect(jsonPath("$.data.rooms[0].title").value("첫 방송"))
                .andExpect(jsonPath("$.data.rooms[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.rooms[0].currentViewers").value(0));
    }

    @Test
    @DisplayName("RTMP: 판매자 시작이 먼저 오고 OBS 연결 웹훅이 나중에 와도 목록에 한 번 나타난다")
    void rtmp_sellerFirstThenWebhook() throws Exception {
        LiveRoom room = rtmpScheduledRoom("RTMP 방송");
        given(liveMediaManager.publishingIngressIdsOrEmpty(room.id())).willReturn(List.of());

        startBySeller(room);
        assertListedRoomIds();

        goLiveByRtmpService.goLiveByRtmp(room.id(), INGRESS_ID, PromotionTrigger.WEBHOOK);
        // 재전송된 같은 웹훅 — 이미 Live 라 no-op 이어야 하고, 목록에도 한 건이다.
        goLiveByRtmpService.goLiveByRtmp(room.id(), INGRESS_ID, PromotionTrigger.WEBHOOK);

        assertListedRoomIds(room.id());
    }

    @Test
    @DisplayName("RTMP: OBS 연결 웹훅이 먼저 오고 판매자 시작이 나중에 와도 같은 결과다")
    void rtmp_webhookFirstThenSeller() throws Exception {
        LiveRoom room = rtmpScheduledRoom("RTMP 방송");
        given(liveMediaManager.publishingIngressIdsOrEmpty(room.id())).willReturn(List.of(INGRESS_ID));

        // 아직 Scheduled — 웹훅은 no-op
        goLiveByRtmpService.goLiveByRtmp(room.id(), INGRESS_ID, PromotionTrigger.WEBHOOK);
        assertListedRoomIds();

        startBySeller(room);

        assertListedRoomIds(room.id());
    }

    @Test
    @DisplayName("다시 등록돼도 이미 쌓인 시청자 수가 0 으로 돌아가지 않는다")
    void reRegisterKeepsViewerCount() throws Exception {
        LiveRoom room = scheduledRoom("방송");
        startBySeller(room);
        redisTemplate.opsForZSet().add("live:ranking", room.id().toString(), 7);

        liveRoomCacheRepository.register(LiveRoomCache.fromLive(liveRoomRepository.findById(room.id()).orElseThrow()));

        mockMvc.perform(get("/api/v1/lives/rooms"))
                .andExpect(jsonPath("$.data.rooms.length()").value(1))
                .andExpect(jsonPath("$.data.rooms[0].currentViewers").value(7));
    }

    @Test
    @DisplayName("판매자가 종료하면 목록에서 사라진다")
    void endedBySellerDisappears() throws Exception {
        LiveRoom room = scheduledRoom("방송");
        startBySeller(room);

        endLiveUseCase.end(new EndLiveCommand(room.id(), room.sellerId()));

        assertListedRoomIds();
        assertThat(redisTemplate.hasKey("live:room:" + room.id())).isFalse();
    }

    @Test
    @DisplayName("배치가 방치된 방송을 종료해도 목록에서 사라진다")
    void endedByBatchDisappears() throws Exception {
        LiveRoom room = scheduledRoom("방송");
        startBySeller(room);

        endStaleLiveUseCase.endStale(new EndStaleLiveCommand(room.id()));

        assertListedRoomIds();
    }

    @Test
    @DisplayName("시작이 커밋 전에 실패해 되돌려지면 목록에 남지 않는다 — 유령 방송 금지")
    void rolledBackStartIsNotListed() throws Exception {
        LiveRoom room = scheduledRoom("방송");
        // 상품 저장은 Live 전이·목록 등록 예약 뒤에 일어난다 — 여기서 터지면 등록 예약이 롤백과 함께 버려져야 한다.
        willThrow(new IllegalStateException("상품 저장 실패")).given(liveProductRepository).saveAll(any());

        // 저장소 프록시가 예외를 DataAccessException 으로 번역한다 — 종류가 아니라 같은 실패인지만 본다.
        assertThatThrownBy(() -> startBySeller(room)).hasMessageContaining("상품 저장 실패");

        assertListedRoomIds();
        assertThat(liveRoomRepository.findById(room.id()).orElseThrow().status())
                .isInstanceOf(LiveStatus.Scheduled.class);
    }

    @Test
    @DisplayName("재조정은 채팅 고유 시청자 수로 순위를 매긴다 — 멀티탭은 한 명")
    void refreshRanksByUniqueChatViewers() throws Exception {
        LiveRoom quiet = scheduledRoom("조용한 방");
        LiveRoom busy = scheduledRoom("붐비는 방");
        startBySeller(quiet);
        startBySeller(busy);
        long now = System.currentTimeMillis();
        redisTemplate.opsForHash().putAll(chatSessions(busy.id()), java.util.Map.of(
                "s1", "alice:" + now, "s2", "alice:" + now, "s3", "bob:" + now));
        redisTemplate.opsForHash().put(chatSessions(quiet.id()), "s1", "carol:" + now);

        refreshLiveListUseCase.refresh();

        mockMvc.perform(get("/api/v1/lives/rooms"))
                .andExpect(jsonPath("$.data.rooms[0].roomId").value(busy.id().toString()))
                .andExpect(jsonPath("$.data.rooms[0].currentViewers").value(2))
                .andExpect(jsonPath("$.data.rooms[1].roomId").value(quiet.id().toString()))
                .andExpect(jsonPath("$.data.rooms[1].currentViewers").value(1));
    }

    @Test
    @DisplayName("재조정은 놓친 등록을 채우고 놓친 제거를 걷어 낸다 — DB 가 원본")
    void refreshRepairsMissedWrites() throws Exception {
        LiveRoom missed = scheduledRoom("등록을 놓친 방");
        startBySeller(missed);
        LiveRoom ended = scheduledRoom("제거를 놓친 방");
        startBySeller(ended);
        LiveRoomCache endedEntry = LiveRoomCache.fromLive(liveRoomRepository.findById(ended.id()).orElseThrow());
        endLiveUseCase.end(new EndLiveCommand(ended.id(), ended.sellerId()));
        // 커밋 후 반영이 실패한 상황을 만든다 — 하나는 지워지고, 하나는 남는다.
        liveRoomCacheRepository.remove(missed.id());
        liveRoomCacheRepository.register(endedEntry);

        refreshLiveListUseCase.refresh();

        assertListedRoomIds(missed.id());
        assertThat(redisTemplate.hasKey("live:room:" + ended.id())).isFalse();
    }

    private static String chatSessions(UUID roomId) {
        return "chat:room:" + roomId + ":sessions";
    }

    @Test
    @DisplayName("예정 목록은 시작 전 방만 예정 시각 순으로, 커서로 끊김 없이 넘긴다 — 시작·종료·취소된 방은 없다")
    void upcomingPagesThroughScheduledAndReadyOnly() throws Exception {
        Instant base = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        LiveRoom first = scheduledRoom("첫째", base);
        LiveRoom tieA = scheduledRoom("동시각 A", base.plusSeconds(60));
        LiveRoom tieB = scheduledRoom("동시각 B", base.plusSeconds(60));
        LiveRoom ready = liveRoomRepository.save(scheduledRoom("준비", base.plusSeconds(120)).arm(Instant.now()));
        LiveRoom started = scheduledRoom("시작함", base.plusSeconds(30));
        startBySeller(started);
        LiveRoom ended = scheduledRoom("끝남", base.plusSeconds(90));
        startBySeller(ended);
        endLiveUseCase.end(new EndLiveCommand(ended.id(), ended.sellerId()));
        LiveRoom cancelled = scheduledRoom("취소함", base.plusSeconds(45));
        liveRoomRepository.save(cancelled.cancel(Instant.now()));

        List<String> tie = java.util.stream.Stream.of(tieA.id(), tieB.id()).map(UUID::toString).sorted().toList();
        List<String> expected = List.of(first.id().toString(), tie.get(0), tie.get(1), ready.id().toString());

        List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var request = get("/api/v1/lives/upcoming").param("size", "2");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            String body = mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andReturn().getResponse().getContentAsString();
            seen.addAll(JsonPath.read(body, "$.data.content[*].roomId"));
            cursor = JsonPath.read(body, "$.data.nextCursor");
            pages++;
        } while (cursor != null && pages < 10);

        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(pages).isEqualTo(2);
    }

    @Test
    @DisplayName("깨진 커서는 400 실패 봉투")
    void upcomingRejectsBrokenCursor() throws Exception {
        mockMvc.perform(get("/api/v1/lives/upcoming").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    private void assertListedRoomIds(UUID... expected) throws Exception {
        String body = mockMvc.perform(get("/api/v1/lives/rooms"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.rooms[*].roomId");
        assertThat(ids).containsExactly(java.util.Arrays.stream(expected).map(UUID::toString).toArray(String[]::new));
    }

    private LiveRoom scheduledRoom(String title) {
        return scheduledRoom(title, Instant.now().plusSeconds(3600));
    }

    private LiveRoom scheduledRoom(String title, Instant scheduledAt) {
        Instant now = Instant.now();
        LiveRoom saved = liveRoomRepository.save(LiveRoom.create(
                UUID.randomUUID(), title, null, "판매자", null, scheduledAt, now));
        return liveRoomRepository.save(saved.withSfuRoomId("sfu-" + saved.id()));
    }

    private LiveRoom rtmpScheduledRoom(String title) {
        LiveRoom room = scheduledRoom(title);
        return liveRoomRepository.save(room.toBuilder().streamType(new LiveStreamType.Rtmp(INGRESS_ID)).build());
    }

    private void startBySeller(LiveRoom room) {
        startLiveUseCase.start(new StartLiveCommand(room.id(), room.sellerId(), List.of(
                new StartLiveCommand.ProductEntry(UUID.randomUUID(), 10_000, 9_000, 8_000, true))));
    }
}
