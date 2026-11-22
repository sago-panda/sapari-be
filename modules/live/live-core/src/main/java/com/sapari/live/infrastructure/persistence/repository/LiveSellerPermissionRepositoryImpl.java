package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.live.domain.repository.LiveSellerPermissionRepository;
import com.sapari.live.infrastructure.persistence.entity.LiveSellerPermissionEntity;

@Repository
@RequiredArgsConstructor
public class LiveSellerPermissionRepositoryImpl implements LiveSellerPermissionRepository {

    private final LiveSellerPermissionJpaRepository jpaRepository;

    @Override
    public boolean isLiveEnabled(UUID sellerId) {
        return jpaRepository.findById(sellerId)
                .map(LiveSellerPermissionEntity::isLiveEnabled)
                .orElse(true);
    }

    @Override
    public void save(UUID sellerId, boolean liveEnabled, Instant updatedAt) {
        jpaRepository.upsert(sellerId, liveEnabled, updatedAt);
    }
}
