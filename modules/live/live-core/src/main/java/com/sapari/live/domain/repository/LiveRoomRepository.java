package com.sapari.live.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.live.domain.model.LiveRoom;

public interface LiveRoomRepository {
    LiveRoom save(LiveRoom liveRoom);
    Optional<LiveRoom> findById(UUID id);
    Optional<LiveRoom> findByIdAndSellerId(UUID id, UUID hostId);
    /**
     * 상태 전이 경합(orphan liveRoom 삭제, ingress_started go-live)을 직렬화하기 위해 행 잠금으로 조회한다.
     */
    Optional<LiveRoom> findByIdForUpdate(UUID id);
}
