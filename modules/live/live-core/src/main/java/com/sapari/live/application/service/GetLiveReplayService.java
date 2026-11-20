package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.exception.LiveReplayNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.VodStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.view.ReplayView;

@Service
@RequiredArgsConstructor
public class GetLiveReplayService implements GetLiveReplayUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public ReplayView getReplay(UUID roomId) {
        LiveRoom room = liveRoomRepository.findById(roomId)
                .orElseThrow(() -> new LiveReplayNotFoundException(roomId.toString()));
        // 비공개로 표시된 방은 아카이브가 있어도 내보내지 않는다. 조회 층에서만 막는다 —
        // 매퍼에서 숨기면 되저장 때 applyStatusFields 의 보존 가드(archive == hls_url)가 걸리지 않아
        // 실제 아카이브 URL 이 null 로 덮이고, 다시 공개로 돌려도 복구되지 않는다.
        // 녹화가 정상 완료(READY)되지 않은 방도 없음으로 본다 — Ended 는 egress 완료를 보장하지 않는다.
        if (!(room.status() instanceof LiveStatus.Ended ended) || !room.vodPublic()
                || room.vodStatus() != VodStatus.READY
                || ended.hlsArchiveUrl() == null || ended.hlsArchiveUrl().isBlank()) {
            throw new LiveReplayNotFoundException(roomId.toString());
        }
        return new ReplayView(room.id(), ended.hlsArchiveUrl(), room.title(), durationSeconds(ended), ended.endedAt());
    }

    /** 다시보기 길이 = 방송 시작~종료(초, 내림). 단건·목록이 같은 값을 쓴다. 시작 전 종료면 null. */
    static Long durationSeconds(LiveStatus.Ended ended) {
        return ended.startedAt() == null ? null : Duration.between(ended.startedAt(), ended.endedAt()).toSeconds();
    }
}
