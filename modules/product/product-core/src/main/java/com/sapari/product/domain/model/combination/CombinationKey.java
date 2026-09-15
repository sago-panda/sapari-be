package com.sapari.product.domain.model.combination;

/**
 * 옵션값 ID 오름차순 조합 문자열 VO (예: "3:7:12"). 상품 내 조합 유일성의 키.
 */
public record CombinationKey(String value) {

    /**
     * 옵션 없는 단일 상품의 기본 조합 키. 옵션값 id(UUID)를 이어 붙인 키와 겹칠 수 없다.
     */
    public static final String DEFAULT = "DEFAULT";

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
