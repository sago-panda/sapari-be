package com.sapari.live.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.UuidTimeEntity;

@Entity
@Getter
@Table(name = "live_products", schema = "live_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveProductEntity extends UuidTimeEntity {

    @Column(nullable = false, updatable = false)
    private UUID liveRoomId;

    @Column(nullable = false, updatable = false)
    private UUID productId;

    @Column(nullable = false, updatable = false)
    private int originalPrice;

    @Column(nullable = false, updatable = false)
    private int discountPrice;

    @Column(nullable = false, updatable = false)
    private int liveDiscountPrice;

    @Column(nullable = false, updatable = false)
    private boolean isPinned;

    @Enumerated(EnumType.STRING)
    @Column(length = 15, updatable = false)
    private DiscountType discountType;

    @Column(updatable = false)
    private Integer discountValue;

    @Column(nullable = false)
    private int sortOrder;

    private Instant pinnedAt;

    @Builder
    public LiveProductEntity(UUID liveRoomId, UUID productId, int originalPrice, int discountPrice,
                             int liveDiscountPrice, boolean isPinned, DiscountType discountType,
                             Integer discountValue, int sortOrder, Instant pinnedAt) {
        this.liveRoomId = liveRoomId;
        this.productId = productId;
        this.originalPrice = originalPrice;
        this.discountPrice = discountPrice;
        this.liveDiscountPrice = liveDiscountPrice;
        this.isPinned = isPinned;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.sortOrder = sortOrder;
        this.pinnedAt = pinnedAt;
    }
}
