package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 조합 가격·재고 일괄 수정 입력(즉시 반영, 재승인 불필요).
 *
 * @param productId    대상 상품 id
 * @param sellerId     요청 판매자 id(소유권 확인용)
 * @param combinations 변경할 조합별 항목들
 */
public record UpdateCombinationPriceStockCommand(
        UUID productId,
        UUID sellerId,
        List<CombinationUpdateCommand> combinations
) {
}
