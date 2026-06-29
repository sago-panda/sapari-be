package com.sapari.product.command;

import java.util.UUID;

/**
 * 판매 상태 전환 입력. {@code ON_SALE} ↔ {@code SUSPENDED}만 허용한다.
 *
 * @param productId 대상 상품 id
 * @param sellerId  요청 판매자 id(소유권 확인용)
 * @param target    전환할 상태명("ON_SALE" 또는 "SUSPENDED")
 */
public record ChangeSaleStatusCommand(
        UUID productId,
        UUID sellerId,
        String target
) {
}
