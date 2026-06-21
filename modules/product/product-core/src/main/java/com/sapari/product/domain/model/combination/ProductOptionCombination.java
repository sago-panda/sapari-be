package com.sapari.product.domain.model.combination;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * 옵션 조합 애그리거트 루트. 재고·가격 단위이며 주문이 id로 직접 참조한다. 소속 옵션값들은 {@code optionValueIds}로 폴딩한다.
 */
@Builder(toBuilder = true)
public record ProductOptionCombination(
        UUID id,
        UUID productId,
        Sku sku,
        CombinationKey combinationKey,
        Integer originalPrice,
        Integer price,
        Stock stock,
        Boolean isAvailable,
        List<UUID> optionValueIds,
        Instant searchIndexedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public ProductOptionCombination {
        if (productId == null) {
            throw new IllegalArgumentException("productId는 필수입니다.");
        }
        if (combinationKey == null) {
            throw new IllegalArgumentException("combinationKey는 필수입니다.");
        }
        if (price == null || price < 0) {
            throw new IllegalArgumentException("price는 0 이상이어야 합니다.");
        }
        if (stock == null) {
            throw new IllegalArgumentException("stock은 필수입니다.");
        }
        optionValueIds = optionValueIds == null ? List.of() : List.copyOf(optionValueIds);
    }

    /**
     * 신규 옵션 조합 생성. 판매 가능({@code isAvailable = true}) 상태로 시작한다.
     *
     * <p>{@code optionValueIds}는 조합을 이루는 옵션값 묶음을 폴딩해 보관하며, 미지정 시 빈 리스트로 정규화한다.
     */
    public static ProductOptionCombination create(
            UUID productId,
            CombinationKey combinationKey,
            Sku sku,
            Integer originalPrice,
            Integer price,
            Stock stock,
            List<UUID> optionValueIds,
            Instant now) {
        return builder()
                .productId(productId)
                .combinationKey(combinationKey)
                .sku(sku)
                .originalPrice(originalPrice)
                .price(price)
                .stock(stock)
                .isAvailable(true)
                .optionValueIds(optionValueIds)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 가용 재고 = stock - reservedStock.
     */
    public int availableStock() {
        return stock == null ? 0 : stock.availableStock();
    }
}
