package com.sapari.live.domain.model;

import lombok.Builder;

import java.util.UUID;

@Builder(toBuilder = true)
public record LiveProduct(
        UUID id,
        UUID liveRoomId,
        UUID productId,
        int originalPrice,
        int discountPrice,
        int liveDiscountPrice,
        boolean isPinned
) {
    /**
     * LiveProduct 도메인 모델 생성
     * id는 null인 상태
     */
    public static LiveProduct create(
            UUID liveRoomId,
            UUID productId,
            int originalPrice,
            int discountPrice,
            int liveDiscountPrice,
            boolean isPinned
    ) {
        return builder()
                .liveRoomId(liveRoomId)
                .productId(productId)
                .originalPrice(originalPrice)
                .discountPrice(discountPrice)
                .liveDiscountPrice(liveDiscountPrice)
                .isPinned(isPinned)
                .build();
    }
}
