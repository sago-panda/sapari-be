package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;

public interface LiveRoomJpaRepository extends JpaRepository<LiveRoomEntity, UUID> {
    Optional<LiveRoomEntity> findByIdAndSellerId(UUID id, UUID sellerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LiveRoomEntity> findWithLockById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<LiveRoomEntity> findWithLockByIdAndSellerId(UUID id, UUID sellerId);

    /** Ready 고착 판정용. arm 시각이 곧 updated_at 이라 "시작 버튼 이후 경과"를 잰다. */
    List<LiveRoomEntity> findByLiveStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            LiveRoomStatus liveStatus, Instant threshold, Limit limit);

    /**
     * Live 고착 판정용. updated_at 을 재사용하면 안 된다 — 방송 중 제목 수정 같은 저장에도 갱신돼
     * 진짜 방치된 방이 후보에서 계속 빠진다. started_at 은 applyLive 이후 변하지 않는다.
     */
    List<LiveRoomEntity> findByLiveStatusAndStartedAtBeforeOrderByStartedAtAsc(
            LiveRoomStatus liveStatus, Instant threshold, Limit limit);
}
