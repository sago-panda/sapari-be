package com.sapari.productapp.controller.product.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.ProductOptionTypeCommand;

/**
 * 옵션 타입 요청 DTO(예: "색상", "사이즈")와 그 값들.
 *
 * @param name             옵션 타입명
 * @param attributeGroupId 카테고리 옵션 템플릿에서 고른 경우 그 그룹 id, 완전 커스텀이면 null
 * @param sortOrder        옵션 선택 계층 순서(1=최상위)
 * @param values           옵션 값 목록(최소 1개)
 */
public record OptionTypeRequest(
        @NotBlank @Size(max = 50) String name,
        UUID attributeGroupId,
        @NotNull Short sortOrder,
        @NotEmpty @Size(max = 100) @Valid List<OptionValueRequest> values
) {

    /**
     * 도메인 비의존 커맨드로 변환한다(값들도 함께 변환).
     *
     * @return 옵션 타입 커맨드
     */
    public ProductOptionTypeCommand toCommand() {
        return new ProductOptionTypeCommand(
                name,
                attributeGroupId,
                sortOrder,
                values.stream().map(OptionValueRequest::toCommand).toList()
        );
    }
}
