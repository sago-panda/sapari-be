package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.IngressResult;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.PrepareIngressCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.IngressCredentialView;

@ExtendWith(MockitoExtension.class)
class PrepareIngressServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private TimeProvider timeProvider;

    @InjectMocks
    private PrepareIngressService prepareIngressService;

    private FixtureMonkey fixtureMonkey;

    private UUID roomId;
    private UUID sellerId;
    private PrepareIngressCommand command;

    @BeforeEach
    void setup() {
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();
        roomId = UUID.randomUUID();
        sellerId = UUID.randomUUID();
        command = new PrepareIngressCommand(roomId, sellerId);
    }

    private LiveRoom scheduledWebRtcRoom() {
        return fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamType", new LiveStreamType.WebRtc())
                .set("streamInfo", StreamInfo.ofSfuRoomId("sfu"))
                .sample();
    }

    @Test
    @DisplayName("발급 성공: ingress 발급 → RTMP 전환 저장 → rtmpUrl·streamKey 반환")
    void prepare_success() {
        Instant now = Instant.parse("2026-06-09T02:00:00Z");
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(scheduledWebRtcRoom()));
        given(liveMediaManager.createIngress(roomId, sellerId))
                .willReturn(new IngressResult("ingress-1", "rtmp://livekit/live", "secret-key"));
        given(timeProvider.now()).willReturn(now);
        given(liveRoomRepository.save(any(LiveRoom.class))).willAnswer(inv -> inv.getArgument(0));

        IngressCredentialView view = prepareIngressService.prepare(command);

        // 반환된 자격
        assertThat(view.ingressId()).isEqualTo("ingress-1");
        assertThat(view.rtmpUrl()).isEqualTo("rtmp://livekit/live");
        assertThat(view.streamKey()).isEqualTo("secret-key");

        // 저장된 방이 RTMP(ingressId 배정)로 전환됐는지
        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should().save(captor.capture());
        LiveRoom saved = captor.getValue();
        assertThat(saved.isRtmp()).isTrue();
        assertThat(((LiveStreamType.Rtmp) saved.streamType()).ingressId()).isEqualTo("ingress-1");
    }

    @Test
    @DisplayName("소유권 없음: 방이 조회되지 않으면 LiveNotFoundException, ingress 발급하지 않는다")
    void prepare_notOwned() {
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> prepareIngressService.prepare(command))
                .isInstanceOf(LiveNotFoundException.class);

        then(liveMediaManager).should(never()).createIngress(any(), any());
    }

    @Test
    @DisplayName("상태 가드: Scheduled 가 아니면 InvalidLiveStateException, ingress 발급하지 않는다")
    void prepare_rejectsNonScheduled() {
        LiveRoom liveRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Live(Instant.now(), "sfu", "eg", "https://hls/1"))
                .set("streamType", new LiveStreamType.WebRtc())
                .set("streamInfo", StreamInfo.ofSfuRoomId("sfu"))
                .sample();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(liveRoom));

        assertThatThrownBy(() -> prepareIngressService.prepare(command))
                .isInstanceOf(InvalidLiveStateException.class);

        then(liveMediaManager).should(never()).createIngress(any(), any());
    }

    @Test
    @DisplayName("멱등: 이미 RTMP ingress 가 발급된 방이면 거부하고 재발급하지 않는다")
    void prepare_rejectsWhenAlreadyRtmp() {
        LiveRoom rtmpRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", new LiveStatus.Scheduled(Instant.now()))
                .set("streamType", new LiveStreamType.Rtmp("existing-ingress"))
                .set("streamInfo", StreamInfo.ofSfuRoomId("sfu"))
                .sample();
        given(liveRoomRepository.findByIdAndSellerId(roomId, sellerId))
                .willReturn(Optional.of(rtmpRoom));

        assertThatThrownBy(() -> prepareIngressService.prepare(command))
                .isInstanceOf(InvalidLiveStateException.class);

        then(liveMediaManager).should(never()).createIngress(any(), any());
    }
}
