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
        Long version,
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

    /**
     * 판매가·정가를 즉시 변경한다 (재승인 불필요). 재고·옵션값 매핑 등 나머지는 보존한다.
     *
     * @throws IllegalArgumentException 가격이 0 미만인 경우(불변식)
     */
    public ProductOptionCombination changePrice(Integer price, Integer originalPrice, Instant now) {
        return toBuilder().price(price)
                .originalPrice(originalPrice)
                .updatedAt(now)
                .build();
    }

    /**
     * 총 재고를 즉시 변경한다. 예약(잠금) 재고는 그대로 유지한다.
     *
     * @throws IllegalArgumentException 새 총 재고가 예약 재고보다 작은 경우(0<=reserved<=stock 불변식)
     */
    public ProductOptionCombination changeStock(Integer stock, Instant now) {
        return toBuilder().stock(Stock.of(stock, this.stock.reservedStock()))
                .updatedAt(now)
                .build();
    }

    /**
     * 단종 처리. {@code isAvailable=false}로 전환한다 (재고 0 품절과 구별되는 판매 중단).
     */
    public ProductOptionCombination discontinue(Instant now) {
        return toBuilder().isAvailable(false)
                .updatedAt(now)
                .build();
    }

    /**
     * 단종된 조합을 다시 판매 가능({@code isAvailable=true})으로 되돌린다.
     */
    public ProductOptionCombination makeAvailable(Instant now) {
        return toBuilder().isAvailable(true)
                .updatedAt(now)
                .build();
    }
}
