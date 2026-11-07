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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.UpdateLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 수정 — 잠금 조회, Scheduled 만, 외부 호출 없음")
class UpdateLiveServiceTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant NEW_AT = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-09T05:00:00Z");

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private TimeProvider timeProvider;
    @InjectMocks private UpdateLiveService updateLiveService;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    private LiveRoom room(LiveStatus status) {
        return LiveRoom.builder().id(roomId).sellerId(sellerId).title("제목").sellerNickname("닉")
                .status(status).scheduledAt(SCHEDULED_AT).build();
    }

    private UpdateLiveCommand command() {
        return new UpdateLiveCommand(roomId, sellerId, "새 제목", "새 설명", "https://cdn/t2.jpg", NEW_AT);
    }

    @Test
    @DisplayName("성공 — 바뀐 값과 새 예정 시각을 저장")
    void updatesScheduledRoom() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT))));
        given(timeProvider.now()).willReturn(NOW);

        updateLiveService.update(command());

        ArgumentCaptor<LiveRoom> saved = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should().save(saved.capture());
        assertThat(saved.getValue().title()).isEqualTo("새 제목");
        assertThat(saved.getValue().thumbnailUrl()).isEqualTo("https://cdn/t2.jpg");
        assertThat(saved.getValue().status()).isEqualTo(new LiveStatus.Scheduled(NEW_AT));
    }

    @Test
    @DisplayName("예약이 아니면 LIVE-014, 저장 없음")
    void rejectsNonScheduled() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Cancelled(SCHEDULED_AT, NOW))));
        given(timeProvider.now()).willReturn(NOW);

        assertThatThrownBy(() -> updateLiveService.update(command()))
                .isInstanceOf(InvalidLiveStateException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.UPDATE_NOT_ALLOWED);
        then(liveRoomRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("없는 방·남의 방 — 404")
    void rejectsMissingOrForeignRoom() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> updateLiveService.update(command())).isInstanceOf(LiveNotFoundException.class);
    }
}
