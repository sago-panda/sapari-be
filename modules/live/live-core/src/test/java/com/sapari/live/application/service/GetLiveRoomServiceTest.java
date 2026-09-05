package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.view.LiveRoomView;

@ExtendWith(MockitoExtension.class)
class GetLiveRoomServiceTest {

    @Mock
    private LiveRoomRepository liveRoomRepository;
    @InjectMocks
    private GetLiveRoomService getLiveRoomService;

    private static final Instant NOW = Instant.parse("2026-07-03T00:00:00Z");
    private static final Instant STARTED_AT = Instant.parse("2026-07-03T01:00:00Z");

    private UUID roomId;
    private UUID sellerId;
    private LiveRoom scheduled;

    @BeforeEach
    void setup() {
        sellerId = UUID.randomUUID();
        roomId = UUID.randomUUID();
        scheduled = LiveRoom.create(sellerId, "타이틀", "설명", "판매자닉", "http://thumb", NOW, NOW)
                .toBuilder().id(roomId).build();
    }

    private LiveRoomView findWith(LiveStatus status) {
        given(liveRoomRepository.findById(roomId))
                .willReturn(Optional.of(scheduled.toBuilder().status(status).build()));
        return getLiveRoomService.findRoom(roomId).orElseThrow();
    }

    @Test
    @DisplayName("없는 방은 Optional.empty를 반환한다")
    void returnsEmpty_whenRoomNotFound() {
        given(liveRoomRepository.findById(roomId)).willReturn(Optional.empty());

        assertThat(getLiveRoomService.findRoom(roomId)).isEmpty();
    }

    @Test
    @DisplayName("Scheduled: 방송 전이라 live=false, startedAt=null")
    void scheduled() {
        LiveRoomView view = findWith(new LiveStatus.Scheduled(NOW));

        assertThat(view.roomId()).isEqualTo(roomId);
        assertThat(view.sellerId()).isEqualTo(sellerId);
        assertThat(view.live()).isFalse();
        assertThat(view.startedAt()).isNull();
    }

    @Test
    @DisplayName("Ready: OBS 연결 대기 중이라 live=false, startedAt=null")
    void ready() {
        LiveRoomView view = findWith(new LiveStatus.Ready(NOW));

        assertThat(view.live()).isFalse();
        assertThat(view.startedAt()).isNull();
    }

    @Test
    @DisplayName("Live: live=true, startedAt은 방송 시작 시각")
    void live() {
        LiveRoomView view = findWith(new LiveStatus.Live(STARTED_AT, "sfu-room", "egress-1", "http://cdn/master.m3u8"));

        assertThat(view.live()).isTrue();
        assertThat(view.startedAt()).isEqualTo(STARTED_AT);
    }

    @Test
    @DisplayName("Ended: live=false, startedAt은 다시보기 싱크를 위해 보존한다")
    void ended() {
        LiveRoomView view = findWith(new LiveStatus.Ended(STARTED_AT, STARTED_AT.plusSeconds(3600), "http://cdn/vod.m3u8"));

        assertThat(view.live()).isFalse();
        assertThat(view.startedAt()).isEqualTo(STARTED_AT);
    }

    @Test
    @DisplayName("Suspended: 송출이 멈춘 상태라 live=false, startedAt은 보존한다")
    void suspended() {
        LiveRoomView view = findWith(new LiveStatus.Suspended(STARTED_AT, STARTED_AT.plusSeconds(600), "신고 누적"));

        assertThat(view.live()).isFalse();
        assertThat(view.startedAt()).isEqualTo(STARTED_AT);
    }
}
