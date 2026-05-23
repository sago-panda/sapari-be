package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.stereotype.Service;

import com.sapari.live.command.GetLiveCommand;
import com.sapari.live.domain.model.LiveRoomCache;
import com.sapari.live.domain.repository.LiveRoomCacheRepository;
import com.sapari.live.port.GetLiveFacade;
import com.sapari.live.view.GetLiveResult;
import com.sapari.live.view.GetLiveResult.LiveRoomSummary;

@Service
@RequiredArgsConstructor
public class GetLiveService implements GetLiveFacade {

    private final LiveRoomCacheRepository liveRoomCacheRepository;

    /**
     * redis에 저장된 누적 시청자 수 많은 순으로 목록 가져오는 메소드
     * redis에 라이브가 없을 경우 빈 리스트 반환
     */
    @Override
    public GetLiveResult getRooms(GetLiveCommand command) {
        List<LiveRoomCache> caches = liveRoomCacheRepository.findTopByViewers(command.limit());

        List<LiveRoomSummary> summaries = caches.stream()
                .map(LiveRoomCache::toSummary)
                .toList();

        return new GetLiveResult(summaries);
    }
}
