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
    /**
     * 소유권 검사 + 행 잠금. 판매자 시작/종료가 webhook go-live·만료 배치와 같은 방을 두고 경합하므로 전이 전에 직렬화한다.
     * 서비스 비교로 쪼개면 "없는 방"과 "남의 방"이 갈려 존재 여부가 새므로 소유권을 쿼리에 남긴다.
     */
    Optional<LiveRoom> findByIdAndSellerIdForUpdate(UUID id, UUID hostId);
}
