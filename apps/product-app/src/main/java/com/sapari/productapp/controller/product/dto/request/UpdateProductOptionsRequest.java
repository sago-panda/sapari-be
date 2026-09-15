package com.sapari.productapp.controller.product.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;

/**
 * 상품 옵션 수정 요청 DTO. 옵션 타입/값을 통째로 교체하고 조합을 재생성한다.
 *
 * <p>추가금/제외 룰·조합별 오버라이드도 새 옵션값 기준으로 통째로 교체된다(생략하면 기존 룰이 지워진다).
 *
 * @param optionTypes     교체할 옵션 타입/값. 비어 있으면 옵션 없는 단일 상품으로 전환
 * @param defaultStock    재생성되는 모든 조합의 기본 재고
 * @param expectedVersion 상세 조회 view.version을 그대로 되돌려준다(stale-form 충돌 감지)
 * @param surchargeRules  조합 추가금 룰(선택, 조합 수 상한과 같은 최대 500개)
 * @param exclusionRules  불가 조합 룰(선택, 최대 500개)
 * @param combinations    조합별 오버라이드(선택, 최대 500개)
 */
public record UpdateProductOptionsRequest(
        @Size(max = 30) List<@NotNull @Valid OptionTypeRequest> optionTypes,
        @PositiveOrZero int defaultStock,
        @NotNull Long expectedVersion,
        @Size(max = 500) List<@NotNull @Valid SurchargeRuleRequest> surchargeRules,
        @Size(max = 500) List<@NotNull @Valid ExclusionRuleRequest> exclusionRules,
        @Size(max = 500) List<@NotNull @Valid CombinationOverrideRequest> combinations
) {

    /**
     * 인증된 판매자 id·대상 상품 id를 결합해 옵션 수정 커맨드로 변환한다.
     *
     * @param sellerId  인증 주체(판매자) id
     * @param productId 수정할 상품 id
     * @return 옵션 수정 커맨드
     */
    public UpdateProductOptionsCommand toCommand(UUID sellerId, UUID productId) {
        return new UpdateProductOptionsCommand(
                productId,
                sellerId,
                toOptionTypeCommands(),
                defaultStock,
                expectedVersion,
                SurchargeRuleRequest.toCommands(surchargeRules),
                ExclusionRuleRequest.toCommands(exclusionRules),
                CombinationOverrideRequest.toCommands(combinations)
        );
    }

    /**
     * 옵션 타입 요청들을 커맨드로 변환한다. null이면 빈 목록을 돌려준다.
     *
     * @return 옵션 타입 커맨드 목록(비어 있을 수 있음)
     */
    private List<ProductOptionTypeCommand> toOptionTypeCommands() {
        if (optionTypes == null) {
            return List.of();
        }
        return optionTypes.stream().map(OptionTypeRequest::toCommand).toList();
    }
}
