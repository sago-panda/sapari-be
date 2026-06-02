package com.sapari.live.command;

import java.util.List;
import java.util.UUID;

public record StartLiveCommand(
        UUID roomId,
        UUID sellerId,
        List<ProductEntry> products
) {
    /**
     * 라이브 상품 등록할 때 들어가는 요소들
     * @param productId: 일반 productId
     * @param originalPrice: 정가
     * @param discountPrice: 일반 할인가
     * @param liveDiscountPrice: 라이브 할인가
     * @param isPinned: 라이브 시작 시 고정 여부
     */
    public record ProductEntry(
            UUID productId,
            int originalPrice,
            int discountPrice,
            int liveDiscountPrice,
            boolean isPinned
    ) {}
}
