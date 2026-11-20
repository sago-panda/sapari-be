package com.sapari.live.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.sapari.live.application.port.LiveViewerCounter;
import com.sapari.live.application.port.RecordingLiveMetrics;
import com.sapari.live.application.port.ViewerCountUnavailableException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;

class RefreshLiveListServiceTest {

    private final LiveRoomRepository roomRepository = mock(LiveRoomRepository.class);
    private final LiveRoomCacheRepository cacheRepository = mock(LiveRoomCacheRepository.class);
    private final LiveViewerCounter viewerCounts = mock(LiveViewerCounter.class);
    private final RecordingLiveMetrics metrics = new RecordingLiveMetrics();
    private final RefreshLiveListService service =
            new RefreshLiveListService(roomRepository, cacheRepository, viewerCounts, metrics);

    @Test
    @DisplayName("DB 의 Live 방과 채팅 시청자 수로 목록을 맞춘다")
    @SuppressWarnings("unchecked")
    void reconcilesWithViewerCounts() {
        LiveRoom room = liveRoom();
        given(roomRepository.findAllLive(RefreshLiveListService.MAX_LIVE_ROOMS + 1)).willReturn(List.of(room));
        given(viewerCounts.countViewers(List.of(room.id()))).willReturn(Map.of(room.id(), 5L));

        service.refresh();

        ArgumentCaptor<List<LiveRoomCache>> live = ArgumentCaptor.forClass(List.class);
        verify(cacheRepository).reconcile(live.capture(), org.mockito.ArgumentMatchers.eq(Map.of(room.id(), 5L)), org.mockito.ArgumentMatchers.eq(true));
        assertThat(live.getValue()).extracting(LiveRoomCache::roomId).containsExactly(room.id());
        assertThat(metrics.listRefreshFailed).isZero();
    }

    @Test
    @DisplayName("방송 중인 방이 없으면 채팅을 묻지 않고 목록을 비운다")
    void emptyLiveClearsWithoutAskingChat() {
        given(roomRepository.findAllLive(RefreshLiveListService.MAX_LIVE_ROOMS + 1)).willReturn(List.of());

        service.refresh();

        verify(viewerCounts, never()).countViewers(anyCollection());
        verify(cacheRepository).reconcile(List.of(), Map.of(), true);
    }

    @Test
    @DisplayName("시청자 수 조회가 실패해도 방 목록은 맞추고, 시청자 수는 넘기지 않아 직전 값이 남는다")
    void viewerCountFailureKeepsPreviousCounts() {
        LiveRoom room = liveRoom();
        given(roomRepository.findAllLive(RefreshLiveListService.MAX_LIVE_ROOMS + 1)).willReturn(List.of(room));
        given(viewerCounts.countViewers(anyCollection()))
                .willThrow(new ViewerCountUnavailableException(new RedisConnectionFailureException("down")));

        service.refresh();

        verify(cacheRepository).reconcile(anyList(), org.mockito.ArgumentMatchers.eq(Map.of()), org.mockito.ArgumentMatchers.eq(true));
        assertThat(metrics.listViewerCountUnavailable).isEqualTo(1);
    }

    @Test
    @DisplayName("목록 저장소 실패는 삼키지 않고, 회차 실패로 센다")
    void cacheFailurePropagates() {
        given(roomRepository.findAllLive(RefreshLiveListService.MAX_LIVE_ROOMS + 1)).willReturn(List.of());
        willThrow(new RedisConnectionFailureException("down")).given(cacheRepository).reconcile(anyList(), anyMap(), org.mockito.ArgumentMatchers.anyBoolean());

        assertThatThrownBy(service::refresh).isInstanceOf(RedisConnectionFailureException.class);
        assertThat(metrics.listRefreshFailed).isEqualTo(1);
    }

    @Test
    @DisplayName("상한을 넘으면 앞쪽만 쓰고 끝난 방은 걷지 않는다 — 읽지 못한 방송을 지우지 않고, 그 회차를 센다")
    @SuppressWarnings("unchecked")
    void saturatedRoundDoesNotPrune() {
        List<LiveRoom> rooms = java.util.stream.IntStream.range(0, RefreshLiveListService.MAX_LIVE_ROOMS + 1)
                .mapToObj(i -> liveRoom()).toList();
        given(roomRepository.findAllLive(RefreshLiveListService.MAX_LIVE_ROOMS + 1)).willReturn(rooms);
        given(viewerCounts.countViewers(anyCollection())).willReturn(Map.of());

        service.refresh();

        ArgumentCaptor<List<LiveRoomCache>> live = ArgumentCaptor.forClass(List.class);
        verify(cacheRepository).reconcile(live.capture(), anyMap(), org.mockito.ArgumentMatchers.eq(false));
        assertThat(live.getValue()).hasSize(RefreshLiveListService.MAX_LIVE_ROOMS);
        assertThat(metrics.listRefreshSaturated).isEqualTo(1);
    }

    private static LiveRoom liveRoom() {
        Instant started = Instant.parse("2026-11-06T05:00:00Z");
        return LiveRoom.builder()
                .id(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .title("방송")
                .sellerNickname("판매자")
                .streamType(new LiveStreamType.WebRtc())
                .status(new LiveStatus.Live(started, "sfu-1", "egress-1", "https://cdn/index.m3u8"))
                .scheduledAt(started)
                .build();
    }
}
