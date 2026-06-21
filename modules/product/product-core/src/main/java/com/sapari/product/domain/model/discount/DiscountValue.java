package com.sapari.product.domain.model.discount;

/**
 * 할인값 VO. 유형과 값을 묶고 불변식(값 > 0, RATE는 1~100)을 보장한다.
 */
public record DiscountValue(DiscountType type, Integer value) {

    /**
     * 값 &gt; 0, RATE 유형은 1~100 불변식을 보장한다. 0 이하 할인이나 100%를 넘는 할인율이 들어가 최종 가격이 음수가 되는 것을 막는다.
     */
    public DiscountValue {
        if (type == null) {
            throw new IllegalArgumentException("discountType은 필수입니다.");
        }
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("discountValue는 0보다 커야 합니다.");
        }
        if (type == DiscountType.RATE && value > 100) {
            throw new IllegalArgumentException("할인율(RATE)은 1~100 사이여야 합니다.");
        }
    }

    /**
     * 유형·값으로 VO를 생성한다(불변식은 생성자에서 검증).
     */
    public static DiscountValue of(DiscountType type, Integer value) {
        return new DiscountValue(type, value);
    }
}
