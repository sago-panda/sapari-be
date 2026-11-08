package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.LiveViewerCounter;
import com.sapari.live.application.port.ViewerCountUnavailableException;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.RefreshLiveListUseCase;

/**
 * 방송 중 목록 재조정. 트랜잭션을 걸지 않는다 — DB 읽기 한 번 뒤의 Redis·채팅 조회를 커넥션을 쥔 채 하지 않는다.
 *
 * <p>[의도된 swallow] 시청자 수 조회 실패({@link ViewerCountUnavailableException})는 지표만 남기고
 * <b>방 목록은 그대로 맞춘다</b>. 이때 시청자 수는 쓰지 않아 직전 값이 남는다(0 으로 덮지 않는다) — 채팅 장애가
 * "모든 방 시청자 0명" 으로 보이면 장애가 아니라 이탈처럼 읽힌다. 목록 저장소 자체의 실패는 그대로 올린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshLiveListService implements RefreshLiveListUseCase {

    /**
     * 한 회차에 맞추는 방 수 상한. 넘으면 id 순 앞쪽만 쓰고 <b>끝난 방 걷기를 건너뛴다</b> — 읽지 못한 방송을 끝난 방으로
     * 오인해 지우지 않기 위해서다. 그 회차는 {@code live.list.refresh.saturated} 로 센다(그 규모가 오면 다시 설계할 신호).
     */
    static final int MAX_LIVE_ROOMS = 1000;

    private final LiveRoomRepository liveRoomRepository;
    private final LiveRoomCacheRepository liveRoomCacheRepository;
    private final LiveViewerCounter liveViewerCounter;
    private final LiveMetrics liveMetrics;

    @Override
    public void refresh() {
        // 정리 잡의 회차 래퍼와 같은 방식 — 아무것도 잡지 않고 finally 에서 실패를 센다(Error 도 센다).
        boolean completed = false;
        try {
            refreshRound();
            completed = true;
        } finally {
            if (!completed) {
                liveMetrics.listRefreshFailed();
            }
        }
    }

    private void refreshRound() {
        List<LiveRoomCache> live = liveRoomRepository.findAllLive(MAX_LIVE_ROOMS + 1).stream()
                .map(LiveRoomCache::fromLive)
                .toList();
        boolean saturated = live.size() > MAX_LIVE_ROOMS;
        if (saturated) {
            live = live.subList(0, MAX_LIVE_ROOMS);
            log.warn("방송 중 방이 재조정 상한({})을 넘었다 — 이번 회차는 끝난 방을 걷지 않는다", MAX_LIVE_ROOMS);
            liveMetrics.listRefreshSaturated();
        }
        liveRoomCacheRepository.reconcile(live, viewerCounts(live), !saturated);
    }

    private Map<UUID, Long> viewerCounts(List<LiveRoomCache> live) {
        if (live.isEmpty()) {
            return Map.of();
        }
        try {
            return liveViewerCounter.countViewers(live.stream().map(LiveRoomCache::roomId).toList());
        } catch (ViewerCountUnavailableException e) {
            log.warn("시청자 수 조회 실패 — 이번 회차는 시청자 수를 갱신하지 않는다", e);
            liveMetrics.listViewerCountUnavailable();
            return Map.of();
        }
    }
}
