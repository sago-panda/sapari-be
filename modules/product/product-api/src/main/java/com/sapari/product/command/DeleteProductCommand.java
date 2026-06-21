package com.sapari.product.command;

import java.util.UUID;

/**
 * 상품 삭제 입력(논리 삭제).
 *
 * @param productId 삭제할 상품 id
 * @param sellerId  요청 판매자 id(소유권 확인용)
 */
public record DeleteProductCommand(
        UUID productId,
        UUID sellerId
) {
}
