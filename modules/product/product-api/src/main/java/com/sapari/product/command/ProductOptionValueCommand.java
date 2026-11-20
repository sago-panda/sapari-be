package com.sapari.product.command;

import java.util.UUID;

/**
 * 옵션 값 입력. 옵션 타입 아래에 중첩되어 상품 등록 시 전달된다. 도메인에 의존하지 않는 표현 DTO다.
 *
 * @param value             옵션 값(예: "빨강", "M")
 * @param attributePresetId 프리셋에서 고른 경우 그 id, 직접 입력이면 null
 * @param metadata          확장 메타데이터 jsonb 문자열(예: {@code {"hex":"#FF0000"}}), 없으면 null
 * @param priceDelta        옵션값별 가격 가산액(원), 없으면 0
 * @param sortOrder         노출 순서
 * @param ref               요청 내 유일한 참조 키. 추가금/제외 룰·조합 오버라이드가 이 값으로 옵션값을 가리킨다.
 *                          참조되지 않는 옵션값이면 null
 */
public record ProductOptionValueCommand(
        String value,
        UUID attributePresetId,
        String metadata,
        int priceDelta,
        short sortOrder,
        String ref
) {
}
