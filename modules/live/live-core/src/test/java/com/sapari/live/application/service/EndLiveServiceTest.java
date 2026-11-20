package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
public class EndLiveServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;

    @Mock
    private LiveMediaManager liveMediaManager;

    @Mock
    private LiveEventPublisher liveEventPublisher;

    @Mock
    private TimeProvider timeProvider;

    @Spy
    private RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();

    @InjectMocks
    private EndLiveService endLiveService;

    private FixtureMonkey fixtureMonkey;
    private UUID roomId;
    private UUID sellerId;

    @BeforeEach
    public void setup(){
        fixtureMonkey = FixtureMonkey.builder()
                .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
                .build();

        roomId = UUID.randomUUID();
        sellerId = UUID.randomUUID();

        // 미디어 정리가 afterCommit 으로 미뤄졌다 — 동기화가 없으면 "트랜잭션 밖" 폴백으로 빠져
        // 즉시 실행되므로, 실제 경로를 밟으려면 트랜잭션 안을 흉내내야 한다.
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** 실제 커밋에서만 도는 훅을 테스트가 직접 발화시킨다. */
    private void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 성공: 정상적인 커맨드가 주어지면 미디어 서버를 종료하고 상태를 저장한다")
    void endLive_Success() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");

        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", liveStatus)
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc())
                .sample();

        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        // when
        endLiveService.end(command);

        // then
        triggerAfterCommit();
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
        then(liveMediaManager).should(times(1)).closeRoom(mockRoom.streamInfo().sfuRoomId());

        ArgumentCaptor<LiveRoom> roomCaptor = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(times(1)).save(roomCaptor.capture());

        // 3. 가로챈 객체의 상태 검증
        LiveRoom savedRoom = roomCaptor.getValue();

        assertThat(savedRoom.id()).isEqualTo(command.roomId());
        assertThat(savedRoom.status()).isInstanceOf(LiveStatus.Ended.class);
    
        // 종료 세 경로(판매자 종료·방치 종료·만료)가 같은 래퍼를 복사한 구조라, 전이 상수를
        // 잘못 넣어도 빌드가 통과한다. 갈래를 여기서 못박는다.
        org.assertj.core.api.Assertions.assertThat(liveMetrics.transitions).containsExactly("Live->Ended");
    }

    @Test
    @DisplayName("RTMP 방 종료: egress 중단 → ingress 삭제 → 방 삭제 순으로 정리한다 (ingress 잔존 시 OBS 재접속이 방을 재생성)")
    void endLive_rtmp_deletesIngressBeforeCloseRoom() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");

        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", liveStatus)
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.Rtmp("ingress-1"))
                .sample();

        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        // when
        endLiveService.end(command);

        // then — 순서 보장: stopHlsEgress → deleteIngress → closeRoom
        triggerAfterCommit();
        var order = inOrder(liveMediaManager);
        order.verify(liveMediaManager).stopHlsEgress(roomId);
        order.verify(liveMediaManager).deleteIngress(roomId);
        order.verify(liveMediaManager).closeRoom("sfu-room-id");
    }

    @Test
    @DisplayName("WebRTC 방 종료: ingress 삭제를 호출하지 않는다")
    void endLive_webrtc_doesNotDeleteIngress() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls.example.com/index.m3u8");

        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId)
                .set("sellerId", sellerId)
                .set("status", liveStatus)
                .set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc())
                .sample();

        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        // when
        endLiveService.end(command);

        // then
        triggerAfterCommit();
        then(liveMediaManager).should(never()).deleteIngress(any());
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 실패: 방을 찾을 수 없거나 권한이 없으면 LiveNotFoundException 발생")
    void endLive_Fail_NotFound() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);

        given(liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId()))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> endLiveService.end(command))
                .isInstanceOf(LiveNotFoundException.class)
                .hasMessageContaining(command.roomId().toString());

        // 미디어 매니저나 save 로직이 절대 실행되지 않았음을 검증
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(times(0)).save(any());
    }

    @RepeatedTest(value = 10)
    @DisplayName("방송 종료 실패: 방송 상태가 LIVE가 아니면 InvalidLiveStateException 발생")
    void endLive_Fail_NotLiveStatus() {
        // given
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        LiveRoom notLiveRoom = LiveRoom.builder()
                .id(command.roomId())
                .sellerId(command.sellerId())
                .status(new LiveStatus.Scheduled(Instant.now()))
                .title("테스트 방송")
                .build();

        given(liveRoomRepository.findByIdAndSellerIdForUpdate(command.roomId(), command.sellerId()))
                .willReturn(Optional.of(notLiveRoom));

        // when & then
        assertThatThrownBy(() -> endLiveService.end(command))
                .isInstanceOf(InvalidLiveStateException.class)
                .hasMessageContaining(command.roomId().toString());

        // 검증
        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(times(0)).save(any());
    }

    @Test
    @DisplayName("미디어 정리는 커밋 이후에 한다 — 커밋 전엔 LiveKit 을 호출하지 않는다(행 잠금 밖으로 뺀 이유)")
    void mediaCleanup_deferredUntilAfterCommit() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls/1");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls/1");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.Rtmp("ingress-1")).sample();
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        endLiveService.end(command);

        // 방은 이미 저장됐지만 LiveKit 에는 아무것도 나가지 않았다 — 잠금은 여기서 풀린다.
        then(liveRoomRepository).should(times(1)).save(any(LiveRoom.class));
        then(liveMediaManager).shouldHaveNoInteractions();

        triggerAfterCommit();
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("트랜잭션 밖 호출이면 즉시 정리한다 — 생략하면 egress 과금이 계속된다(RoomEnded 와 다른 분기)")
    void mediaCleanup_runsImmediately_whenNoTransaction() {
        TransactionSynchronizationManager.clearSynchronization(); // 트랜잭션 없는 호출 재현

        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls/1");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls/1");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());

        endLiveService.end(command);

        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("정리 중 예외는 삼킨다 — 방은 이미 Ended 로 커밋됐고, 판매자가 LiveKit 사정을 알 이유가 없다")
    void mediaCleanupFailure_isSwallowed() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        LiveStatus.Live liveStatus = new LiveStatus.Live(Instant.now(), "sfu-room-id", "egress-id", "http://hls/1");
        StreamInfo streamInfo = StreamInfo.of("sfu-room-id", "egress-id", "http://hls/1");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(Instant.now());
        org.mockito.BDDMockito.willThrow(new IllegalStateException("LiveKit 장애"))
                .given(liveMediaManager).stopHlsEgress(roomId);

        endLiveService.end(command);

        org.assertj.core.api.Assertions.assertThatCode(this::triggerAfterCommit).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("종료 커밋 후 RoomEnded를 발행한다 (afterCommit)")
    void publishesRoomEnded_afterCommit() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        Instant endedAt = Instant.parse("2026-07-03T00:00:00Z");
        LiveStatus.Live liveStatus = new LiveStatus.Live(endedAt, "sfu", "eg", "http://hls/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu", "eg", "http://hls/index.m3u8");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(endedAt);

        endLiveService.end(command);

        // 커밋 전에는 미발행
        then(liveEventPublisher).shouldHaveNoInteractions();

        triggerAfterCommit();
        then(liveEventPublisher).should(times(1)).publishRoomEnded(roomId, endedAt);
    }

    @Test
    @DisplayName("종료가 롤백되면(afterCommit 미호출) RoomEnded를 발행하지 않는다")
    void doesNotPublish_whenRolledBack() {
        EndLiveCommand command = new EndLiveCommand(roomId, sellerId);
        Instant endedAt = Instant.parse("2026-07-03T00:00:00Z");
        LiveStatus.Live liveStatus = new LiveStatus.Live(endedAt, "sfu", "eg", "http://hls/index.m3u8");
        StreamInfo streamInfo = StreamInfo.of("sfu", "eg", "http://hls/index.m3u8");
        LiveRoom mockRoom = fixtureMonkey.giveMeBuilder(LiveRoom.class)
                .set("id", roomId).set("sellerId", sellerId)
                .set("status", liveStatus).set("streamInfo", streamInfo)
                .set("streamType", new LiveStreamType.WebRtc()).sample();
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.of(mockRoom));
        given(timeProvider.now()).willReturn(endedAt);

        endLiveService.end(command);

        // 롤백 시나리오: afterCommit 을 호출하지 않는다 → 발행도 미디어 정리도 없음
        then(liveEventPublisher).shouldHaveNoInteractions();
        then(liveMediaManager).shouldHaveNoInteractions();
    }
}
