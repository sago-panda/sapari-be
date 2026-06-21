package com.sapari.product.domain.model.optionattribute;

import java.util.UUID;

/**
 * 옵션 속성 그룹의 사전 정의 값 VO ({@link OptionAttributeGroup} 애그리거트 내부).
 *
 * <p>예: "색상" 그룹의 빨강/파랑처럼 드롭박스에 제시되는 선택지. {@code value}는 필수이며,
 * compact 생성자에서 검증한다.
 */
public record OptionPreset(
        UUID id,
        String value,
        Integer sortOrder
) {

    public OptionPreset {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("preset value는 필수입니다.");
        }
    }

    /**
     * 영속 전(id 없이) value·sortOrder로 생성. sortOrder 누락 시 {@code 0}.
     */
    public static OptionPreset of(String value, Integer sortOrder) {
        return new OptionPreset(null, value, sortOrder == null ? 0 : sortOrder);
    }
}
