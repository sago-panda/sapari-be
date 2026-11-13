package com.sapari.product.view;

import java.util.UUID;

/**
 * 대량 등록에서 저장된 상품 한 건.
 *
 * @param productCode 파일의 상품 코드
 * @param productId   저장된 상품 id
 */
public record BulkCreatedProductView(
        String productCode,
        UUID productId
) {
}
