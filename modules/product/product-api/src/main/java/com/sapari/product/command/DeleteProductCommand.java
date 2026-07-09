package com.sapari.product.command;

import java.util.UUID;

/**
 * 상품 삭제 입력(논리 삭제).
 *
 * @param productId 삭제할 상품 id
 * @param sellerId  요청 판매자 id(소유권 확인용)
 * @param expectedVersion 클라이언트가 본 상품 version(stale-form 충돌 감지용)
 */
public record DeleteProductCommand(
        UUID productId,
        UUID sellerId,
        Long expectedVersion
) {
}
