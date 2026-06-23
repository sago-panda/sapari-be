package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 옵션 타입 입력(예: "색상", "사이즈")과 그 값들. 상품 등록 시 전달되는 표현 DTO다.
 *
 * @param name             옵션 타입명
 * @param attributeGroupId 카테고리 옵션 템플릿에서 고른 경우 그 그룹 id, 완전 커스텀이면 null
 * @param sortOrder        옵션 선택 계층 순서(1=최상위)
 * @param values           옵션 값 목록
 */
public record ProductOptionTypeCommand(
        String name,
        UUID attributeGroupId,
        short sortOrder,
        List<ProductOptionValueCommand> values
) {
}
