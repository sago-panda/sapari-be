package com.sapari.apiapp.controller.live.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import com.sapari.live.command.StartLiveCommand.ProductEntry;

public record StartBroadcastRequest(
        @NotEmpty(message = "라이브 상품은 최소 1개 이상이어야 합니다.")

        List<@NotNull @Valid ProductRequest> products
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
            @NotNull(message = "상품 ID는 필수입니다.")
            UUID productId,
            @Positive(message = "정가는 0보다 커야 합니다.")
            int originalPrice,
            @PositiveOrZero(message = "할인가는 0 이상이어야 합니다.")
            int discountPrice,
            @PositiveOrZero(message = "라이브 할인가는 0 이상이어야 합니다.")
            int liveDiscountPrice,
            boolean isPinned
    ) {}
}
