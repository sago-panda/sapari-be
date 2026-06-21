package com.sapari.product.domain.model.discount;

/**
 * 할인 유형 (도메인). 엔티티 enum과 분리(도메인은 infrastructure를 의존하지 않음).
 */
public enum DiscountType {
    RATE,        // 할인율(%)
    FIXED_AMOUNT // 할인 금액(원)
}
