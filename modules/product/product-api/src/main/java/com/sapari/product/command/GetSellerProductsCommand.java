package com.sapari.product.command;

import java.util.UUID;

/**
 * 판매자 상품 목록 조회 입력(커서 기반 무한 스크롤).
 *
 * @param sellerId 조회 대상 판매자 id(인증 주체)
 * @param cursor   이전 페이지가 돌려준 불투명 커서. null이면 첫 페이지
 * @param size     페이지 크기. null·0 이하는 기본값, 상한 초과는 상한으로 정규화된다
 */
public record GetSellerProductsCommand(
        UUID sellerId,
        String cursor,
        Integer size
) {
}
