package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sapari.live.domain.exception.LiveErrorCode;
import com.sapari.live.domain.exception.LiveReplayNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.ReplayView;

class GetLiveReplayServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private final UUID roomId = UUID.randomUUID();
    private final LiveRoomRepository repository = mock(LiveRoomRepository.class);
    private final GetLiveReplayService service = new GetLiveReplayService(repository);

    @ParameterizedTest
    @ValueSource(strings = {"https://cdn/live/720p/playlist.m3u8", "https://cdn/live/archive-master.m3u8?v=1",
            "https://cdn/live/720p/playlist.m3u8?v=1#start", "https://cdn/replays/renamed-event.m3u8?v=2"})
    void endedRoomReturnsPublicArchive(String archive) {
        givenRoom(new LiveStatus.Ended(NOW, NOW.plusSeconds(440), archive));

        assertThat(service.getReplay(roomId)).isEqualTo(new ReplayView(roomId, archive));
    }

    @ParameterizedTest
    @MethodSource("unavailableStatuses")
    void unavailableReplayIsNotFound(LiveStatus status) {
        givenRoom(status);

        assertThatThrownBy(() -> service.getReplay(roomId)).isInstanceOf(LiveReplayNotFoundException.class);
        assertThat(LiveErrorCode.LIVE_REPLAY_NOT_FOUND.getStatus()).isEqualTo(404);
    }

    static Stream<LiveStatus> unavailableStatuses() {
        return Stream.of(new LiveStatus.Scheduled(NOW), new LiveStatus.Ready(NOW),
                new LiveStatus.Live(NOW, "sfu", "egress", "https://cdn/index.m3u8"),
                new LiveStatus.Suspended(NOW, NOW, "중단"),
                new LiveStatus.Ended(null, NOW, null),
                new LiveStatus.Ended(NOW, NOW.plusSeconds(440), ""),
                new LiveStatus.Ended(NOW, NOW.plusSeconds(440), "   "));
    }

    @Test
    void missingRoomIsNotFound() {
        given(repository.findById(roomId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.getReplay(roomId)).isInstanceOf(LiveReplayNotFoundException.class);
    }

    private void givenRoom(LiveStatus status) {
        given(repository.findById(roomId)).willReturn(Optional.of(
                LiveRoom.builder().id(roomId).status(status).build()));
    }
}
