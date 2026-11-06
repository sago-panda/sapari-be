package com.sapari.product.view;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 상품 목록 요약 뷰. 목록 카드 표시에 필요한 최소 정보만 담는다.
 *
 * @param productId    상품 id
 * @param name         상품명
 * @param status       상품 상태명
 * @param minPrice     할인 후 최저 조합 가격(캐시), 없으면 null
 * @param hasStock     재고 있는 조합 존재 여부(캐시)
 * @param thumbnailKey 대표 이미지(GALLERY) 스토리지 키, 없으면 null
 * @param reviewCount  리뷰 수(캐시)
 * @param avgRating    평균 별점(캐시), 리뷰 없으면 null
 * @param version      낙관적 락 버전(상품). 수정 폼이 받아 expectedVersion으로 되돌려준다(stale-form 차단)
 */
public record ProductSummaryView(
        UUID productId,
        String name,
        String status,
        Integer minPrice,
        Boolean hasStock,
        String thumbnailKey,
        Integer reviewCount,
        BigDecimal avgRating,
        Long version
) {
}
