package com.sapari.product.view;

import java.util.UUID;

/**
 * 옵션 값 상세 뷰.
 *
 * @param optionValueId 옵션 값 id
 * @param value         옵션 값(예: "빨강", "M")
 * @param metadata      확장 메타데이터 jsonb 문자열, 없으면 null
 * @param priceDelta    옵션값별 가격 가산액(원)
 * @param sortOrder     노출 순서
 */
public record OptionValueView(
        UUID optionValueId,
        String value,
        String metadata,
        Integer priceDelta,
        Short sortOrder
) {
}
