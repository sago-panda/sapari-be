package com.sapari.productapp.controller.product.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.UpdateCombinationPriceStockCommand;

/**
 * 조합 가격·재고 일괄 수정 요청 DTO(즉시 반영, 재승인 불필요).
 *
 * @param combinations 변경할 조합별 항목들(최소 1개)
 */
public record UpdateCombinationPriceStockRequest(
        @NotEmpty @Size(max = 500) @Valid List<CombinationUpdateRequest> combinations
) {

    /**
     * 인증된 판매자 id·대상 상품 id를 결합해 조합 수정 커맨드로 변환한다.
     *
     * @param sellerId  인증 주체(판매자) id
     * @param productId 대상 상품 id
     * @return 조합 가격·재고 수정 커맨드
     */
    public UpdateCombinationPriceStockCommand toCommand(UUID sellerId, UUID productId) {
        return new UpdateCombinationPriceStockCommand(
                productId,
                sellerId,
                combinations.stream().map(CombinationUpdateRequest::toCommand).toList()
        );
    }
}
