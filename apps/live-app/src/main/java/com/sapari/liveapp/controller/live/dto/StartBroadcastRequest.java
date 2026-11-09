package com.sapari.liveapp.controller.live.dto;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.live.command.StartLiveCommand.ProductEntry;

public record StartBroadcastRequest(
        @NotEmpty(message = "라이브 상품은 최소 1개 이상이어야 합니다.")
        @Size(max = MAX_PRODUCTS, message = "라이브 상품은 최대 50개입니다.")
        List<@NotNull @Valid ProductRequest> products
) {
    public static final int MAX_PRODUCTS = 50;

    /**
     * 같은 productId 가 두 번 오면 거부한다(가격이 달라도). 방 조회보다 먼저(@Valid) 걸리므로 부작용이 없다.
     * null 원소·null productId 는 각자의 @NotNull 이 보고하도록 여기선 건너뛴다.
     */
    @AssertTrue(message = "상품 ID가 중복되었습니다.")
    public boolean isProductIdsUnique() {
        if (products == null) {
            return true;
        }
        Set<UUID> seen = new HashSet<>();
        return products.stream()
                .filter(Objects::nonNull)
                .map(ProductRequest::productId)
                .filter(Objects::nonNull)
                .allMatch(seen::add);
    }

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
