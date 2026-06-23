package com.sapari.product.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 상품 상세 뷰. 기본 정보 + 태그 + 이미지 + 옵션 트리 + 옵션 조합을 조립해 돌려준다.
 *
 * @param productId             상품 id
 * @param sellerId              판매자 id
 * @param categoryId            카테고리 id
 * @param name                  상품명
 * @param description           상세 설명
 * @param basePrice             기본 가격
 * @param status                상품 상태명
 * @param minPrice              할인 후 최저 조합 가격(캐시), 없으면 null
 * @param hasStock              재고 있는 조합 존재 여부(캐시)
 * @param shippingPolicyId      배송 정책 id, 없으면 null
 * @param additionalShippingFee 상품 단독 배송비
 * @param avgRating             평균 별점(캐시), 리뷰 없으면 null
 * @param reviewCount           리뷰 수(캐시)
 * @param tags                  상품 태그
 * @param images                상품·옵션 이미지
 * @param optionTypes           옵션 타입/값 트리
 * @param combinations          옵션 조합(재고·가격 단위)
 */
public record ProductDetailView(
        UUID productId,
        UUID sellerId,
        Long categoryId,
        String name,
        String description,
        Integer basePrice,
        String status,
        Integer minPrice,
        Boolean hasStock,
        UUID shippingPolicyId,
        Integer additionalShippingFee,
        BigDecimal avgRating,
        Integer reviewCount,
        List<String> tags,
        List<ProductImageView> images,
        List<OptionTypeView> optionTypes,
        List<CombinationView> combinations
) {
}
