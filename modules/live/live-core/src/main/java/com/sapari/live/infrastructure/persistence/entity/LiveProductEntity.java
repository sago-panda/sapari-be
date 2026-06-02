package com.sapari.live.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.BaseUuidEntity;

@Entity
@Getter
@Table(name = "live_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveProductEntity extends BaseUuidEntity {

    @Column(name = "live_room_id", nullable = false, updatable = false)
    private UUID liveRoomId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "original_price", nullable = false, updatable = false)
    private int originalPrice;

    @Column(name = "discount_price", nullable = false, updatable = false)
    private int discountPrice;

    @Column(name = "live_discount_price", nullable = false, updatable = false)
    private int liveDiscountPrice;

    @Column(name = "is_pinned", nullable = false, updatable = false)
    private boolean isPinned;

    @Builder
    public LiveProductEntity(UUID liveRoomId, UUID productId, int originalPrice, int discountPrice, int liveDiscountPrice, boolean isPinned) {
        this.liveRoomId = liveRoomId;
        this.productId = productId;
        this.originalPrice = originalPrice;
        this.discountPrice = discountPrice;
        this.liveDiscountPrice = liveDiscountPrice;
        this.isPinned = isPinned;
    }
}
