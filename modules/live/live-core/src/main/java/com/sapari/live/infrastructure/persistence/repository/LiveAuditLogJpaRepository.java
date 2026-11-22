package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.live.infrastructure.persistence.entity.LiveAuditLogEntity;

public interface LiveAuditLogJpaRepository extends JpaRepository<LiveAuditLogEntity, UUID> {

    /** {@code detail} 은 DB 가 {@code jsonb_build_object} 로 만든다 — 사유의 따옴표·역슬래시 이스케이프를 맡긴다. */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.audit_logs
                   (actor_id, actor_type, action, target_id, target_type, detail, created_at)
            VALUES (:adminId, 'ADMIN', 'LIVE_ADMIN_END', :roomId, 'LIVE_ROOM',
                    jsonb_build_object('reason', CAST(:reason AS text)), :occurredAt)
            """, nativeQuery = true)
    void insertAdminEnd(@Param("adminId") UUID adminId,
                        @Param("roomId") UUID roomId,
                        @Param("reason") String reason,
                        @Param("occurredAt") Instant occurredAt);

    /** 관리자 판매자 라이브 권한 변경. {@code detail} 은 바꾼 값과 사유 — 이스케이프는 {@link #insertAdminEnd} 와 같다. */
    @Modifying
    @Query(value = """
            INSERT INTO live_schema.audit_logs
                   (actor_id, actor_type, action, target_id, target_type, detail, created_at)
            VALUES (:adminId, 'ADMIN', 'LIVE_ADMIN_SELLER_PERMISSION', :sellerId, 'SELLER',
                    jsonb_build_object('liveEnabled', CAST(:liveEnabled AS boolean),
                                       'reason', CAST(:reason AS text)), :occurredAt)
            """, nativeQuery = true)
    void insertSellerLivePermission(@Param("adminId") UUID adminId,
                                    @Param("sellerId") UUID sellerId,
                                    @Param("liveEnabled") boolean liveEnabled,
                                    @Param("reason") String reason,
                                    @Param("occurredAt") Instant occurredAt);
}
