package com.sapari.product.domain.model.product;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * 상품 목록 조회용 읽기 모델. 목록 카드에 필요한 스칼라와 대표 이미지 키만 담는다(옵션·조합 등 자식 미포함).
 * 영속 어댑터가 프로젝션 쿼리로 직접 채운다 — 풀 애그리거트를 로딩하지 않는다.
 *
 * @param id           상품 id
 * @param name         상품명
 * @param status       상품 상태
 * @param minPrice     할인 후 최저 조합 가격(캐시), 없으면 null
 * @param hasStock     재고 있는 조합 존재 여부(캐시)
 * @param reviewCount  리뷰 수(캐시)
 * @param avgRating    평균 별점(캐시), 리뷰 없으면 null
 * @param thumbnailKey 대표 이미지(GALLERY 최소 sortOrder) 스토리지 키, 없으면 null
 */
public record ProductSummary(
        UUID id,
        String name,
        ProductStatus status,
        Integer minPrice,
        Boolean hasStock,
        Integer reviewCount,
        BigDecimal avgRating,
        String thumbnailKey
) {
}
