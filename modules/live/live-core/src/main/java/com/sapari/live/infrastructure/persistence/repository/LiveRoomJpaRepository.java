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

    List<LiveRoomEntity> findByLiveStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            LiveRoomStatus liveStatus, Instant threshold, Limit limit);
}
