package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetLiveRoomUseCase;
import com.sapari.live.view.LiveRoomView;

/**
 * 권한 판정용 단건 조회. 랭킹 목록(GetLiveService)은 Redis 캐시를 읽으므로 여기서 쓰지 않고
 * DB 정본을 본다. 읽기 전용이라 행 잠금은 걸지 않는다.
 */
@Service
@RequiredArgsConstructor
public class GetLiveRoomService implements GetLiveRoomUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<LiveRoomView> findRoom(UUID roomId) {
        return liveRoomRepository.findById(roomId).map(GetLiveRoomService::toView);
    }

    private static LiveRoomView toView(LiveRoom room) {
        LiveStatus status = room.status();
        boolean live = status instanceof LiveStatus.Live;
        return new LiveRoomView(room.id(), room.sellerId(), live, startedAtOf(status));
    }

    // default 를 쓰지 않는다 — 상태가 추가되면 컴파일이 깨져서 알려주도록 다섯 갈래를 모두 나열한다.
    private static Instant startedAtOf(LiveStatus status) {
        return switch (status) {
            case LiveStatus.Scheduled ignored -> null;
            case LiveStatus.Ready ignored -> null;
            case LiveStatus.Live l -> l.startedAt();
            case LiveStatus.Ended e -> e.startedAt();
            case LiveStatus.Suspended s -> s.startedAt();
        };
    }
}
