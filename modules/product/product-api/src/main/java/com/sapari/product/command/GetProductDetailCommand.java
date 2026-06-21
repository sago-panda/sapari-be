package com.sapari.product.command;

import java.util.UUID;

/**
 * 상품 상세 조회 입력.
 *
 * @param productId 조회할 상품 id
 */
public record GetProductDetailCommand(
        UUID productId
) {
}
