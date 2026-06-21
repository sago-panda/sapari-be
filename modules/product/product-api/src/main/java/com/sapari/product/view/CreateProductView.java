package com.sapari.product.view;

import java.util.UUID;

/**
 * 상품 등록 결과. 생성된 상품 id와 현재 상태(문자열)를 돌려준다.
 *
 * @param productId 생성된 상품 id
 * @param status    상품 상태명(예: {@code PENDING_REVIEW})
 */
public record CreateProductView(
        UUID productId,
        String status
) {
}
