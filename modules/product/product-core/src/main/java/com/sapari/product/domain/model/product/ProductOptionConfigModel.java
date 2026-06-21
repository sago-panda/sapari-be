package com.sapari.product.domain.model.product;

/**
 * 상품별 옵션 저작 룰 (조합 추가금 + 불가 조합). jsonb 문자열로 보관.
 */
public record ProductOptionConfigModel(
        String surchargeRules,
        String exclusionRules
) {
    public static ProductOptionConfigModel of(String surchargeRules, String exclusionRules) {
        return new ProductOptionConfigModel(surchargeRules, exclusionRules);
    }
}
