package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.HlsEgressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.domain.exception.BroadcastStartException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
class GoLiveByRtmpServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;
    @Mock
    private LiveMediaManager liveMediaManager;
    @Mock
    private TimeProvider timeProvider;

    @Spy
    private RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();

    @InjectMocks
    private GoLiveByRtmpService goLiveByRtmpService;

    private FixtureMonkey fixtureMonkey;
    private UUID roomId;

    @BeforeEach
    void setup() {
        // 보상 훅(registerSynchronization)은 활성 동기화를 요구 — 단위 테스트에는 실제 tx가 없으므로 수동 초기화
        TransactionSynchronizationManager.initSynchronization();
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();
        roomId = UUID.randomUUID();
    }

    @AfterEach
    void clearTxSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LiveRoom room(LiveStatus status, LiveStreamType streamType) {
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("status", status)
                .set("streamInfo", new StreamInfo("sfu-1", null, null))
                .set("streamType", streamType)
                .sample();
    }

    /** 승격 가능한 상태(Ready + RTMP + 방이 인정하는 ingress)를 세팅한다. */
    private void givenPromotableRoom() {
        LiveRoom ready = room(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ready));
        given(liveMediaManager.startHlsEgress(roomId))
                .willReturn(new HlsEgressResult("egress-1", "http://hls/index.m3u8"));
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(inv -> inv.getArgument(0));
    }

    /** 이미 Live 라 전이 대상이 아닌 방. */
    private void givenNonPromotableRoom() {
        LiveRoom live = room(new LiveStatus.Live(Instant.parse("2026-06-10T10:00:00Z"),
                        "sfu-1", "egress-1", "http://hls/index.m3u8"),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(live));
    }

    @Test
    @DisplayName("Ready+RTMP 방: egress 시작하고 Live 로 전이·저장한다")
    void goesLive_whenReadyRtmp() {
        LiveRoom ready = room(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ready));
        given(liveMediaManager.startHlsEgress(roomId))
                .willReturn(new HlsEgressResult("egress-1", "http://hls/index.m3u8"));
        given(timeProvider.now()).willReturn(Instant.now());
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(inv -> inv.getArgument(0));

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);

        verify(liveMediaManager).startHlsEgress(roomId);
        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        verify(liveRoomRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(LiveStatus.Live.class);
    }

    @Test
    @DisplayName("아직 Scheduled(상품 미등록)면 no-op — egress·save 없음(멱등)")
    void noop_whenStillScheduled() {
        LiveRoom scheduled = room(new LiveStatus.Scheduled(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(scheduled));

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
        verify(liveRoomRepository, never()).save(any(LiveRoom.class));
    }

    @Test
    @DisplayName("이미 Live 로 전이된 방이면 no-op — 재전송/리플레이/랑데부 선처리 안전(멱등)")
    void noop_whenAlreadyLive() {
        LiveRoom live = room(new LiveStatus.Live(Instant.now(), "sfu-1", "egress-1", "http://hls/index.m3u8"),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(live));

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
        verify(liveRoomRepository, never()).save(any(LiveRoom.class));
    }

    @Test
    @DisplayName("방을 찾을 수 없으면 LiveNotFoundException")
    void throws_whenRoomNotFound() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK))
                .isInstanceOf(LiveNotFoundException.class)
                .hasMessageContaining(roomId.toString());
    }

    @Test
    @DisplayName("이벤트의 ingressId 가 이 방의 것이 아니면 no-op — 회수 실패로 살아남은 ingress 가 방을 올리지 못하게")
    void noop_whenIngressIdDoesNotMatch() {
        LiveRoom ready = room(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ready));

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-LOSER", PromotionTrigger.WEBHOOK);

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
        verify(liveRoomRepository, never()).save(any(LiveRoom.class));
    }

    @Test
    @DisplayName("webhook 이 ingressId 를 안 실어 오면(null/blank) 거부한다 — 여기서 통과시키면 대조가 통째로 빠진다")
    void rejects_whenWebhookEventCarriesNoIngressId() {
        LiveRoom ready = room(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ready));

        goLiveByRtmpService.goLiveByRtmp(roomId, null, PromotionTrigger.WEBHOOK);
        goLiveByRtmpService.goLiveByRtmp(roomId, "  ", PromotionTrigger.WEBHOOK);

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
        verify(liveRoomRepository, never()).save(any(LiveRoom.class));
    }

    @Test
    @DisplayName("트랜잭션 동기화가 비활성이면 egress 시작 전에 BroadcastStartException — 고아 egress 방지 사전 가드")
    void throws_before_egress_when_synchronization_inactive() {
        LiveRoom ready = room(new LiveStatus.Ready(Instant.parse("2026-06-10T10:00:00Z")),
                new LiveStreamType.Rtmp("ing-1"));
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ready));
        // tx 없이 호출되는 회귀 상황 재현 (setup 의 initSynchronization 을 되돌림)
        TransactionSynchronizationManager.clearSynchronization();

        assertThatThrownBy(() -> goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK))
                .isInstanceOf(BroadcastStartException.class)
                .hasMessageContaining("트랜잭션 동기화 비활성");

        verify(liveMediaManager, never()).startHlsEgress(any(UUID.class));
    }

    @Test
    @DisplayName("webhook 으로 승격하면 trigger=WEBHOOK 으로 센다 — 경로를 나눠 세는 것이 이 인자의 존재 이유다")
    void promotedByWebhook_isTaggedWebhook() {
        givenPromotableRoom();

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);

        org.assertj.core.api.Assertions.assertThat(liveMetrics.promotions)
                .containsExactly(PromotionTrigger.WEBHOOK);
        org.assertj.core.api.Assertions.assertThat(liveMetrics.transitions)
                .containsExactly("Ready->Live");
    }

    @Test
    @DisplayName("정리 잡으로 승격하면 trigger=RECONCILE 로 센다 — 이 값이 늘면 실시간 감지가 새는 것이다")
    void promotedByReconcile_isTaggedReconcile() {
        givenPromotableRoom();

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.RECONCILE);

        org.assertj.core.api.Assertions.assertThat(liveMetrics.promotions)
                .containsExactly(PromotionTrigger.RECONCILE);
    }

    @Test
    @DisplayName("전이 대상이 아니면 승격도 전이도 세지 않는다")
    void notPromotable_countsNothing() {
        givenNonPromotableRoom();

        goLiveByRtmpService.goLiveByRtmp(roomId, "ing-1", PromotionTrigger.WEBHOOK);

        org.assertj.core.api.Assertions.assertThat(liveMetrics.promotions).isEmpty();
        org.assertj.core.api.Assertions.assertThat(liveMetrics.transitions).isEmpty();
    }
}
