package com.sapari.productapp.controller.product.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.productapp.validation.JsonObjectString;

/**
 * 옵션 값 요청 DTO(예: "빨강", "M"). 옵션 타입 아래에 중첩된다.
 *
 * @param value             옵션 값
 * @param attributePresetId 프리셋에서 고른 경우 그 id, 직접 입력이면 null
 * @param metadata          확장 메타데이터 jsonb 문자열(예: {@code {"hex":"#FF0000"}}), 없으면 null.
 *                          JSON 객체 파싱 가능성·크기를 검증한다. 키 화이트리스트·깊은 구조 검증(m-2)은 후속.
 * @param priceDelta        옵션값별 가격 가산액(원). 음수 허용(할인 옵션)
 * @param sortOrder         노출 순서
 */
public record OptionValueRequest(
        @NotBlank @Size(max = 100) String value,
        UUID attributePresetId,
        @JsonObjectString @Size(max = 2048) String metadata,
        int priceDelta,
        @NotNull Short sortOrder
) {

    /**
     * 도메인 비의존 커맨드로 변환한다.
     *
     * @return 옵션 값 커맨드
     */
    public ProductOptionValueCommand toCommand() {
        return new ProductOptionValueCommand(value, attributePresetId, metadata, priceDelta, sortOrder);
    }
}
