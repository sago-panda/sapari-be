package com.sapari.product.command;

import java.util.UUID;

/**
 * 판매자 상품 목록 조회 입력.
 *
 * @param sellerId 조회 대상 판매자 id(인증 주체)
 */
public record GetSellerProductsCommand(
        UUID sellerId
) {
}
