package com.sapari.live.infrastructure.persistence.repository;

import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.sapari.live.domain.repository.LiveAuditLogRepository;

@Repository
@RequiredArgsConstructor
public class LiveAuditLogRepositoryImpl implements LiveAuditLogRepository {

    private final LiveAuditLogJpaRepository jpaRepository;

    @Override
    public void appendAdminEnd(UUID adminId, UUID roomId, String reason, Instant occurredAt) {
        jpaRepository.insertAdminEnd(adminId, roomId, reason, occurredAt);
    }

    @Override
    public void appendSellerLivePermission(UUID adminId, UUID sellerId, boolean liveEnabled, String reason,
                                           Instant occurredAt) {
        jpaRepository.insertSellerLivePermission(adminId, sellerId, liveEnabled, reason, occurredAt);
    }
}
