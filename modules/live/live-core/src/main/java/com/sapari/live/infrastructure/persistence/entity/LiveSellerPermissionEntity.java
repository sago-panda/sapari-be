package com.sapari.live.infrastructure.persistence.entity;

import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code live_schema.live_seller_permission} 매핑 — 판매자마다 한 행.
 *
 * <p>쓰기는 {@code LiveSellerPermissionJpaRepository#upsert} 네이티브 쿼리가 맡고, 이 클래스로 저장하지 않는다
 * — 처음 바꾸는 판매자는 행이 없어 {@code merge} 로는 동시 요청에서 PK 위반이 난다.
 */
@Getter
@Entity
@Table(name = "live_seller_permission", schema = "live_schema")
public class LiveSellerPermissionEntity {

    @Id
    @Column(name = "seller_id", nullable = false, updatable = false)
    private UUID sellerId;

    @Column(name = "live_enabled", nullable = false)
    private boolean liveEnabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LiveSellerPermissionEntity() {
    }
}
