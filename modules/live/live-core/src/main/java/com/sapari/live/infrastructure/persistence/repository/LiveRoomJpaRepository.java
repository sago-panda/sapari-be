package com.sapari.live.infrastructure.persistence.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;

public interface LiveRoomJpaRepository extends JpaRepository<LiveRoomEntity, UUID> {
    Optional<LiveRoomEntity> findByIdAndSellerId(UUID id, UUID sellerId);
}
