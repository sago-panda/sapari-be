package com.sapari.productapp.controller.product.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.SurchargeRuleCommand;

/**
 * 조합 추가금 룰 요청 DTO. 지정한 옵션값을 모두 포함하는 조합에 금액을 가산한다.
 *
 * <p>ref 존재·타입당 1개 같은 구조 규칙은 요청 전체를 봐야 하므로 서비스(PRODUCT-004)가 검증한다.
 *
 * @param valueRefs 매칭 조건 옵션값 ref 목록(옵션 타입 수 상한과 같은 최대 10개)
 * @param amount    가산 금액(원)
 */
public record SurchargeRuleRequest(
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 100) String> valueRefs,
        @PositiveOrZero int amount
) {

    /**
     * 요청 목록을 커맨드 목록으로 변환한다. null이면 룰 없음이므로 빈 목록.
     *
     * @param requests 추가금 룰 요청들
     * @return 추가금 룰 커맨드 목록(비어 있을 수 있음)
     */
    static List<SurchargeRuleCommand> toCommands(List<SurchargeRuleRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(SurchargeRuleRequest::toCommand).toList();
    }

    /**
     * 도메인 비의존 커맨드로 변환한다.
     *
     * @return 추가금 룰 커맨드
     */
    public SurchargeRuleCommand toCommand() {
        return new SurchargeRuleCommand(valueRefs, amount);
    }
}
