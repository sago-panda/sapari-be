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
        if (liveRoomId == null) {
            throw new IllegalArgumentException("liveRoomId는 필수입니다.");
        }
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        if (originalPrice < 0 || discountPrice < 0 || liveDiscountPrice < 0) {
            throw new IllegalArgumentException("가격은 음수일 수 없습니다.");
        }
        if (discountPrice > originalPrice) {
            throw new IllegalArgumentException("할인가는 정가를 초과할 수 없습니다.");
        }
        if (liveDiscountPrice > discountPrice) {
            throw new IllegalArgumentException("라이브 할인가는 일반 할인가를 초과할 수 없습니다.");
        }
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
