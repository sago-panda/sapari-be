package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveEventPublisher;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.command.CancelLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 취소 — 커밋 전 미디어 호출 0, 커밋 후 정리, RoomEnded 없음")
class CancelLiveServiceTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-09T05:00:00Z");

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private LiveMediaManager liveMediaManager;
    @Mock private TimeProvider timeProvider;
    @Spy private RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();
    @InjectMocks private CancelLiveService cancelLiveService;

    // 서비스에 주입되지 않는다는 것 자체가 FR-004 의 구조적 보장이다. 아래 테스트는 그래도 호출 0 을 확인한다.
    @Mock private LiveEventPublisher liveEventPublisher;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void triggerAfterCommit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    private LiveRoom room(LiveStatus status, LiveStreamType streamType) {
        return LiveRoom.builder()
                .id(roomId).sellerId(sellerId).title("제목").sellerNickname("닉")
                .status(status).streamType(streamType)
                .streamInfo(StreamInfo.ofSfuRoomId(roomId.toString()))
                .scheduledAt(SCHEDULED_AT)
                .build();
    }

    @Test
    @DisplayName("성공 — 잠금 조회 → Cancelled 저장, 미디어 정리는 커밋 후에만")
    void cancelsScheduledRoomAndCleansUpAfterCommit() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT), new LiveStreamType.WebRtc())));
        given(timeProvider.now()).willReturn(NOW);

        cancelLiveService.cancel(new CancelLiveCommand(roomId, sellerId));

        ArgumentCaptor<LiveRoom> saved = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should().save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(new LiveStatus.Cancelled(SCHEDULED_AT, NOW));
        assertThat(liveMetrics.transitions).containsExactly("Scheduled->Cancelled");
        // 트랜잭션 안에서는 LiveKit 을 부르지 않는다
        then(liveMediaManager).shouldHaveNoInteractions();

        triggerAfterCommit();

        InOrder order = inOrder(liveMediaManager);
        order.verify(liveMediaManager).stopHlsEgress(roomId);
        order.verify(liveMediaManager).closeRoom(roomId.toString());
        then(liveMediaManager).should(never()).deleteIngress(any());
        then(liveEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("RTMP 키를 발급받은 방 — 커밋 후 ingress 를 방 닫기 전에 지운다")
    void deletesIngressBeforeClosingRoom() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT), new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(NOW);

        cancelLiveService.cancel(new CancelLiveCommand(roomId, sellerId));
        triggerAfterCommit();

        InOrder order = inOrder(liveMediaManager);
        order.verify(liveMediaManager).deleteIngress(roomId);
        order.verify(liveMediaManager).closeRoom(roomId.toString());
    }

    @Test
    @DisplayName("예약이 아니면 LIVE-013, 저장·미디어 호출 없음")
    void rejectsNonScheduled() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Ready(SCHEDULED_AT), new LiveStreamType.Rtmp("ing-1"))));
        given(timeProvider.now()).willReturn(NOW);

        assertThatThrownBy(() -> cancelLiveService.cancel(new CancelLiveCommand(roomId, sellerId)))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.CANCEL_NOT_ALLOWED);

        then(liveRoomRepository).should(never()).save(any());
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        then(liveMediaManager).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("없는 방·남의 방 — 404")
    void rejectsMissingOrForeignRoom() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cancelLiveService.cancel(new CancelLiveCommand(roomId, sellerId)))
                .isInstanceOf(LiveNotFoundException.class);
        then(liveMediaManager).shouldHaveNoInteractions();
    }
}
