package com.sapari.product.command;

import java.util.List;

/**
 * 조합별 오버라이드 입력. 서버가 자동 생성한 조합 중 하나를 골라 기본값 대신 개별 값을 지정한다.
 *
 * <p>조합은 옵션 타입마다 값 하나씩을 가리키는 {@code valueRefs}로 식별한다. null 필드는 기본값을 유지한다.
 *
 * @param valueRefs     대상 조합의 옵션값 ref 목록(옵션 타입마다 정확히 1개)
 * @param price         판매가(원), null이면 서버 계산값(기본가 + 가산액 + 추가금 룰)
 * @param originalPrice 정가(원, 취소선 표시용), null이면 미설정
 * @param stock         재고, null이면 기본 재고
 * @param sku           SKU 코드(상품 내 유일), null이면 미설정
 */
public record CombinationOverrideCommand(
        List<String> valueRefs,
        Integer price,
        Integer originalPrice,
        Integer stock,
        String sku
) {
}
