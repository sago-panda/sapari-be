package com.sapari.product.view;

import java.util.List;
import java.util.UUID;

/**
 * 옵션 타입 상세 뷰(예: "색상", "사이즈")와 그 값들.
 *
 * @param optionTypeId 옵션 타입 id
 * @param name         옵션 타입명
 * @param sortOrder    옵션 선택 계층 순서
 * @param values       옵션 값 목록
 */
public record OptionTypeView(
        UUID optionTypeId,
        String name,
        Short sortOrder,
        List<OptionValueView> values
) {
}
