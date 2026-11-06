package com.sapari.productapp.controller.product.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.ExclusionRuleCommand;

/**
 * 불가 조합 룰 요청 DTO. 지정한 옵션값을 모두 포함하는 조합은 생성하지 않는다.
 *
 * <p>ref 존재·타입당 1개 같은 구조 규칙은 요청 전체를 봐야 하므로 서비스(PRODUCT-004)가 검증한다.
 *
 * @param valueRefs 매칭 조건 옵션값 ref 목록(옵션 타입 수 상한과 같은 최대 10개)
 * @param reason    제외 사유(선택)
 */
public record ExclusionRuleRequest(
        @NotEmpty @Size(max = 10) List<@NotBlank @Size(max = 100) String> valueRefs,
        @Size(max = 200) String reason
) {

    /**
     * 요청 목록을 커맨드 목록으로 변환한다. null이면 룰 없음이므로 빈 목록.
     *
     * @param requests 제외 룰 요청들
     * @return 제외 룰 커맨드 목록(비어 있을 수 있음)
     */
    static List<ExclusionRuleCommand> toCommands(List<ExclusionRuleRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        return requests.stream().map(ExclusionRuleRequest::toCommand).toList();
    }

    /**
     * 도메인 비의존 커맨드로 변환한다.
     *
     * @return 제외 룰 커맨드
     */
    public ExclusionRuleCommand toCommand() {
        return new ExclusionRuleCommand(valueRefs, reason);
    }
}
