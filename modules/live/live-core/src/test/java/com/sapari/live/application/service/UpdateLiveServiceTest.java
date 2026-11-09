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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.command.UpdateLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("예약 수정 — 잠금 조회, Scheduled 만, 외부 호출 없음")
class UpdateLiveServiceTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2026-06-10T10:00:00Z");
    private static final Instant NEW_AT = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-06-09T05:00:00Z");

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private TimeProvider timeProvider;
    @Spy private ThumbnailUrlRule thumbnailUrlRule = ThumbnailUrlRule.of("https://cdn.example.com");
    @InjectMocks private UpdateLiveService updateLiveService;

    private final UUID roomId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    private LiveRoom room(LiveStatus status) {
        return LiveRoom.builder().id(roomId).sellerId(sellerId).title("제목").sellerNickname("닉")
                .status(status).scheduledAt(SCHEDULED_AT).build();
    }

    private String thumbnail() {
        return "https://cdn.example.com/thumbnails/" + sellerId + "/44444444-4444-4444-4444-444444444444";
    }

    private UpdateLiveCommand command() {
        return command(thumbnail(), NEW_AT);
    }

    private UpdateLiveCommand command(String thumbnailUrl, Instant scheduledAt) {
        return new UpdateLiveCommand(roomId, sellerId, "새 제목", "새 설명", thumbnailUrl, scheduledAt);
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
        assertThat(saved.getValue().thumbnailUrl()).isEqualTo(thumbnail());
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
    @DisplayName("수정 불가 상태 + 다른 썸네일 — 썸네일(LIVE-016)보다 상태(LIVE-014)가 먼저")
    void stateCheckedBeforeThumbnail() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Cancelled(SCHEDULED_AT, NOW))));
        given(timeProvider.now()).willReturn(NOW);

        assertThatThrownBy(() -> updateLiveService.update(command("https://other.com/x.jpg", NEW_AT)))
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

    @Test
    @DisplayName("썸네일을 바꾸면서 다른 판매자 경로를 주면 LIVE-016, 저장 없음")
    void rejectsForeignThumbnail() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT))));
        String foreign = "https://cdn.example.com/thumbnails/" + UUID.randomUUID() + "/44444444-4444-4444-4444-444444444444";

        assertThatThrownBy(() -> updateLiveService.update(command(foreign, NEW_AT)))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.INVALID_THUMBNAIL_URL);
        then(liveRoomRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("썸네일을 바꾸지 않으면 재검증하지 않는다 — 규칙 이전에 만든 방의 기존 썸네일로도 수정 가능")
    void keepsLegacyThumbnailWhenUnchanged() {
        String legacy = "https://legacy.example.com/old.jpg";
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT)).toBuilder().thumbnailUrl(legacy).build()));
        given(timeProvider.now()).willReturn(NOW);

        updateLiveService.update(command(legacy, NEW_AT));

        ArgumentCaptor<LiveRoom> saved = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should().save(saved.capture());
        assertThat(saved.getValue().thumbnailUrl()).isEqualTo(legacy);
        assertThat(saved.getValue().title()).isEqualTo("새 제목");
    }

    @Test
    @DisplayName("기존 썸네일을 다른 비규칙 주소로 바꾸면 LIVE-016")
    void rejectsChangeToNonCdnThumbnail() {
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT)).toBuilder()
                        .thumbnailUrl("https://legacy.example.com/old.jpg").build()));

        assertThatThrownBy(() -> updateLiveService.update(command("https://legacy.example.com/new.jpg", NEW_AT)))
                .isInstanceOf(InvalidLiveRoomException.class);
        then(liveRoomRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("예정 시각이 현재와 같으면 LIVE-015, 저장 없음")
    void rejectsNonFutureSchedule() {
        given(liveRoomRepository.findByIdAndSellerIdForUpdate(roomId, sellerId))
                .willReturn(Optional.of(room(new LiveStatus.Scheduled(SCHEDULED_AT))));
        given(timeProvider.now()).willReturn(NOW);

        assertThatThrownBy(() -> updateLiveService.update(command(thumbnail(), NOW)))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE);
        then(liveRoomRepository).should(never()).save(any());
    }
}
