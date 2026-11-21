package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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
import com.sapari.live.command.AdminEndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.StreamInfo;
import com.sapari.live.domain.repository.LiveAuditLogRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("관리자 종료 — 방 주인 확인 없이 Live 방을 Cancelled 로 보내고 감사를 남긴다")
class AdminEndLiveServiceTest {

    private static final Instant SCHEDULED = Instant.parse("2026-06-10T08:55:00Z");
    private static final Instant STARTED = Instant.parse("2026-06-10T09:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private LiveAuditLogRepository liveAuditLogRepository;
    @Mock private LiveMediaManager liveMediaManager;
    @Mock private LiveEventPublisher liveEventPublisher;
    @Mock private TimeProvider timeProvider;
    @Spy private RecordingLiveMetrics liveMetrics = new RecordingLiveMetrics();
    @Mock private LiveListCacheSync liveListCacheSync;

    @InjectMocks
    private AdminEndLiveService adminEndLiveService;

    private UUID roomId;
    private UUID adminId;

    @BeforeEach
    void setup() {
        roomId = UUID.randomUUID();
        adminId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private LiveRoom liveRoom() {
        return LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(new LiveStatus.Live(STARTED, "sfu-1", "eg-1", "https://hls/1"))
                .streamInfo(StreamInfo.of("sfu-1", "eg-1", "https://hls/1", null))
                .streamType(new LiveStreamType.WebRtc())
                .scheduledAt(SCHEDULED)
                .build();
    }

    @Test
    @DisplayName("Live 방 — 시작·취소 시각을 남긴 Cancelled 로 저장하고 관리자·방·사유로 감사 1건을 남긴다")
    void end_live_savesCancelledAndAppendsAudit() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(liveRoom()));
        given(timeProvider.now()).willReturn(NOW);

        adminEndLiveService.end(new AdminEndLiveCommand(roomId, adminId, "음란물 송출"));

        ArgumentCaptor<LiveRoom> saved = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(times(1)).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(new LiveStatus.Cancelled(SCHEDULED, STARTED, NOW));
        then(liveAuditLogRepository).should(times(1)).appendAdminEnd(adminId, roomId, "음란물 송출", NOW);
        assertThat(liveMetrics.transitions).containsExactly("Live->Cancelled");
    }

    @Test
    @DisplayName("판매자 종료와 같은 정리 — 커밋 뒤에만 미디어 정리·RoomEnded 발행·목록 제거")
    void end_live_cleansUpAfterCommitOnly() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(liveRoom()));
        given(timeProvider.now()).willReturn(NOW);

        adminEndLiveService.end(new AdminEndLiveCommand(roomId, adminId, "사유"));

        then(liveMediaManager).shouldHaveNoInteractions();
        then(liveEventPublisher).shouldHaveNoInteractions();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        then(liveMediaManager).should(times(1)).stopHlsEgress(roomId);
        then(liveMediaManager).should(times(1)).closeRoom("sfu-1");
        then(liveEventPublisher).should(times(1)).publishRoomEnded(roomId, NOW);
        then(liveListCacheSync).should(times(1)).removeAfterCommit(roomId);
    }

    static Stream<Arguments> notEndableStatuses() {
        return Stream.of(
                Arguments.of(new LiveStatus.Scheduled(NOW), new LiveStreamType.WebRtc()),
                Arguments.of(new LiveStatus.Ready(NOW), new LiveStreamType.Rtmp("ing-1")),
                Arguments.of(new LiveStatus.Ended(STARTED, NOW, null), new LiveStreamType.WebRtc()),
                Arguments.of(new LiveStatus.Cancelled(NOW, NOW), new LiveStreamType.WebRtc()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notEndableStatuses")
    @DisplayName("Live·Suspended 가 아니면 LIVE-009 — 저장·감사·미디어 호출이 없다")
    void end_notEndable_rejectsWithoutSideEffects(LiveStatus status, LiveStreamType streamType) {
        LiveRoom room = LiveRoom.builder()
                .id(roomId)
                .sellerId(UUID.randomUUID())
                .title("제목")
                .sellerNickname("닉네임")
                .status(status)
                .streamType(streamType)
                .build();
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.of(room));

        assertThatThrownBy(() -> adminEndLiveService.end(new AdminEndLiveCommand(roomId, adminId, "사유")))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_END_NOT_ALLOWED);

        then(liveRoomRepository).should(never()).save(any(LiveRoom.class));
        then(liveAuditLogRepository).shouldHaveNoInteractions();
        then(liveMediaManager).shouldHaveNoInteractions();
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    @DisplayName("방이 없으면 LIVE-002 — 감사를 남기지 않는다")
    void end_roomNotFound() {
        given(liveRoomRepository.findByIdForUpdate(roomId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> adminEndLiveService.end(new AdminEndLiveCommand(roomId, adminId, "사유")))
                .isInstanceOf(LiveNotFoundException.class);

        then(liveAuditLogRepository).shouldHaveNoInteractions();
    }
}
