package com.sapari.live.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sapari.live.infrastructure.persistence.entity.LiveSellerPermissionEntity;

public interface LiveSellerPermissionJpaRepository extends JpaRepository<LiveSellerPermissionEntity, UUID> {

    /**
     * 트랜잭션은 {@code ChangeSellerLivePermissionService} 가 연다. 네이티브 UPDATE 는 영속성 컨텍스트를 거치지 않으므로
     * 비운다 — 같은 트랜잭션에서 앞서 읽은 엔티티가 남아 있으면 다음 {@code findById} 가 바뀌기 전 값을 돌려준다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO live_schema.live_seller_permission (seller_id, live_enabled, updated_at)
            VALUES (:sellerId, :liveEnabled, :updatedAt)
            ON CONFLICT (seller_id)
            DO UPDATE SET live_enabled = EXCLUDED.live_enabled, updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsert(@Param("sellerId") UUID sellerId,
                @Param("liveEnabled") boolean liveEnabled,
                @Param("updatedAt") Instant updatedAt);
}
