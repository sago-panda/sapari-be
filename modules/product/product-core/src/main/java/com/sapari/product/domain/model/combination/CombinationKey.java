package com.sapari.product.domain.model.combination;

/**
 * 옵션값 ID 오름차순 조합 문자열 VO (예: "3:7:12"). 상품 내 조합 유일성의 키.
 */
public record CombinationKey(String value) {

    public CombinationKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("combinationKey는 필수입니다.");
        }
    }

    /**
     * 조합 키 생성.
     */
    public static CombinationKey of(String value) {
        return new CombinationKey(value);
    }
}
