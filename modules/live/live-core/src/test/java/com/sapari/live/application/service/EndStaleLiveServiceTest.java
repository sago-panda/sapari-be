package com.sapari.live.application.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndStaleLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
class EndStaleLiveServiceTest {

    private static final Instant STARTED = Instant.parse("2026-06-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

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
    private EndStaleLiveService endStaleLiveService;

    private UUID roomId;

    @BeforeEach
    void setup() {
        roomId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LiveRoom liveRoom(LiveStreamType streamType) {
        return LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Live(STARTED, "sfu-1", "eg-1", "https://hls/1"))
                .streamInfo(StreamInfo.of("sfu-1", "eg-1", "https://hls/1"))
                .streamType(streamType)
                .build();
    }

    /** afterCommit 훅은 실제 커밋에서만 돌므로 테스트가 직접 트리거한다. */
    private void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    @DisplayName("RTMP 방: egress 중단 → ingress 삭제 → 방 삭제 순으로 정리하고 Ended 로 저장한다")
    void endStale_rtmp_cleansUpInOrder() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(liveRoom(new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        triggerAfterCommit();
        var order = inOrder(liveMediaManager);
        order.verify(liveMediaManager).stopHlsEgress(roomId);
        order.verify(liveMediaManager).deleteIngress(roomId);
        order.verify(liveMediaManager).closeRoom("sfu-1");

        ArgumentCaptor<LiveRoom> captor = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(times(1)).save(captor.capture());
        assertThat(captor.getValue().status()).isInstanceOf(LiveStatus.Ended.class);
    
        org.assertj.core.api.Assertions.assertThat(liveMetrics.transitions).containsExactly("Live->Ended");
    }

    @Test
    @DisplayName("Live 였던 방이므로 커밋 후 RoomEnded 를 발행한다 — 안 하면 시청자가 죽은 방에 남는다")
    void endStale_publishesRoomEndedAfterCommit() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(liveRoom(new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        // 커밋 전에는 발행하지 않는다(롤백 시 오발행 방지)
        then(liveEventPublisher).should(never()).publishRoomEnded(any(UUID.class), any(Instant.class));

        triggerAfterCommit();
        then(liveEventPublisher).should(times(1)).publishRoomEnded(roomId, NOW);
    }

    @Test
    @DisplayName("WebRTC 방: ingress 삭제를 호출하지 않는다")
    void endStale_webrtc_doesNotDeleteIngress() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(liveRoom(new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        triggerAfterCommit();
        then(liveMediaManager).should(never()).deleteIngress(roomId);
    }

    @Test
    @DisplayName("미디어 정리는 커밋 이후에 한다 — 커밋 전엔 LiveKit 을 호출하지 않는다(행 잠금 밖으로 뺀 이유)")
    void endStale_mediaCleanupDeferredUntilAfterCommit() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(liveRoom(new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        then(liveRoomRepository).should(times(1)).save(any(LiveRoom.class));
        then(liveMediaManager).shouldHaveNoInteractions();

        triggerAfterCommit();
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }

    @Test
    @DisplayName("조회는 행 잠금으로 한다 — 판매자 종료와 직렬화되지 않으면 이중 정리가 난다")
    void endStale_readsWithRowLock() {
        given(liveRoomRepository.findByIdForUpdate(roomId))
                .willReturn(Optional.of(liveRoom(new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        then(liveRoomRepository).should(times(1)).findByIdForUpdate(roomId);
        then(liveRoomRepository).should(never()).findById(roomId);
    }

    @Test
    @DisplayName("방이 없으면 LiveNotFoundException — 미디어 정리도 저장도 하지 않는다")
    void endStale_roomNotFound() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> endStaleLiveService.endStale(new EndStaleLiveCommand(roomId)))
                .isInstanceOf(LiveNotFoundException.class);

        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveRoomRepository).should(never()).save(any(LiveRoom.class));
    }

    @Test
    @DisplayName("이미 종료된 방이면 InvalidLiveStateException — LiveKit 에 아무것도 나가지 않는다")
    void endStale_alreadyEnded_throwsBeforeAnyMediaCall() {
        LiveRoom ended = LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Ended(STARTED, NOW, null))
                .streamType(new LiveStreamType.WebRtc())
                .build();
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(ended));

        assertThatThrownBy(() -> endStaleLiveService.endStale(new EndStaleLiveCommand(roomId)))
                .isInstanceOf(InvalidLiveStateException.class);

        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("SFU 방이 없는 방(방송 전 Suspended)은 closeRoom 을 건너뛴다")
    void endStale_withoutSfuRoom_skipsCloseRoom() {
        // Live 는 sfuRoomId 를 필수 검증하므로 이 상태가 될 수 없다. canEndLive 가 함께 허용하는
        // Suspended(방송 전 정지 → startedAt·streamInfo 없음)가 가드가 실제로 걸리는 경로다.
        LiveRoom room = LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Suspended(null, NOW, "관리자 정지"))
                .streamType(new LiveStreamType.WebRtc())
                .build();
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(room));
        given(timeProvider.now()).willReturn(NOW);

        endStaleLiveService.endStale(new EndStaleLiveCommand(roomId));

        triggerAfterCommit();
        then(liveMediaManager).should(never()).closeRoom(anyString());
        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
    }
}
