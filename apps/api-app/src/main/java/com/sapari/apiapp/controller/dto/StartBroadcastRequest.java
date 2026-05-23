package com.sapari.apiapp.controller.dto;

import java.util.List;
import java.util.UUID;

import com.sapari.live.command.StartLiveCommand.ProductEntry;

public record StartBroadcastRequest(
        List<ProductRequest> products
) {
    public List<ProductEntry> toProductEntries() {
        return products.stream()
                .map(p -> new ProductEntry(p.productId(), p.originalPrice(), p.discountPrice(), p.liveDiscountPrice(), p.isPinned()))
                .toList();
    }

    /**
     * LiveProduct 생성 요청
     */
    public record ProductRequest(
            UUID productId,
            int originalPrice,
            int discountPrice,
            int liveDiscountPrice,
            boolean isPinned
    ) {}
}
