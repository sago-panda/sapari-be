package com.sapari.productapp.controller.product.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import com.sapari.product.command.CombinationUpdateCommand;

/**
 * 단일 조합의 가격·재고·판매여부 변경 요청 DTO. null 필드는 "변경 안 함"을 의미한다.
 *
 * @param combinationId 변경할 조합 id
 * @param price         새 판매가, null이면 가격 변경 없음
 * @param originalPrice 새 비교 정가(취소선). price가 지정될 때 함께 적용되며, null이면 정가 제거
 * @param stock         새 총 재고, null이면 재고 변경 없음
 * @param isAvailable   판매 가능 여부, null이면 변경 없음(true=재개, false=단종)
 * @param expectedVersion 조합 상세 view.version을 그대로 되돌려준다(조합별 stale-form 충돌 감지)
 */
public record CombinationUpdateRequest(
        @NotNull UUID combinationId,
        @PositiveOrZero Integer price,
        @PositiveOrZero Integer originalPrice,
        @PositiveOrZero Integer stock,
        Boolean isAvailable,
        @NotNull Long expectedVersion
) {

    /**
     * 도메인 비의존 커맨드로 변환한다.
     *
     * @return 조합 수정 커맨드
     */
    public CombinationUpdateCommand toCommand() {
        return new CombinationUpdateCommand(combinationId, price, originalPrice, stock, isAvailable, expectedVersion);
    }
}
