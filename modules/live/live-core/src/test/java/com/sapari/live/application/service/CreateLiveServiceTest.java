package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
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
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.LiveDomainException;
import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.ThumbnailUrlRule;
import com.sapari.live.domain.repository.LiveRoomRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("방 생성 — 썸네일(LIVE-016) → 예정 시각(LIVE-015) → 저장 → SFU")
class CreateLiveServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final UUID SELLER = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String THUMB =
            "https://cdn.example.com/thumbnails/" + SELLER + "/44444444-4444-4444-4444-444444444444";

    @Mock private LiveRoomRepository liveRoomRepository;
    @Mock private LiveMediaManager liveMediaManager;
    @Mock private TimeProvider timeProvider;
    @Spy private ThumbnailUrlRule thumbnailUrlRule = ThumbnailUrlRule.of("https://cdn.example.com");
    @InjectMocks private CreateLiveService createLiveService;

    private static CreateLiveCommand command(String thumbnailUrl, Instant scheduledAt, String description) {
        return new CreateLiveCommand(SELLER, "제목", description, "닉", thumbnailUrl, scheduledAt);
    }

    @Test
    @DisplayName("정상 — 저장 후 SFU 방을 만든다, 공백 설명은 없음으로 저장")
    void creates() {
        UUID roomId = UUID.randomUUID();
        given(timeProvider.now()).willReturn(NOW);
        given(liveRoomRepository.save(any())).willAnswer(inv -> {
            LiveRoom room = inv.getArgument(0);
            return room.id() == null ? room.toBuilder().id(roomId).build() : room;
        });
        given(liveMediaManager.createRoom(roomId)).willReturn(new SfuRoomResult("sfu-1", 100, true));

        createLiveService.create(command(THUMB, NOW.plusMillis(1), "   "));

        ArgumentCaptor<LiveRoom> saved = ArgumentCaptor.forClass(LiveRoom.class);
        then(liveRoomRepository).should(org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().getFirst().description()).isNull();
        assertThat(saved.getAllValues().getFirst().thumbnailUrl()).isEqualTo(THUMB);
    }

    @Test
    @DisplayName("다른 판매자의 썸네일 — LIVE-016, 저장·SFU 없음")
    void rejectsForeignThumbnail() {
        String foreign = "https://cdn.example.com/thumbnails/" + UUID.randomUUID() + "/44444444-4444-4444-4444-444444444444";

        assertRejected(command(foreign, NOW.plusSeconds(60), "설명"), LiveErrorCode.INVALID_THUMBNAIL_URL);
    }

    @Test
    @DisplayName("썸네일과 예정 시각이 함께 틀리면 썸네일(LIVE-016)이 먼저")
    void thumbnailCheckedBeforeSchedule() {
        assertRejected(command("https://other.com/x.jpg", NOW.minusSeconds(1), "설명"), LiveErrorCode.INVALID_THUMBNAIL_URL);
    }

    @Test
    @DisplayName("예정 시각이 현재 이하 — LIVE-015, 저장·SFU 없음")
    void rejectsNonFutureSchedule() {
        given(timeProvider.now()).willReturn(NOW);

        assertRejected(command(THUMB, NOW, "설명"), LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE);
    }

    private void assertRejected(CreateLiveCommand command, LiveErrorCode code) {
        assertThatThrownBy(() -> createLiveService.create(command))
                .isInstanceOf(InvalidLiveRoomException.class)
                .extracting(e -> ((LiveDomainException) e).getErrorCode())
                .isEqualTo(code);
        then(liveRoomRepository).should(never()).save(any());
        then(liveMediaManager).should(never()).createRoom(any());
    }
}
