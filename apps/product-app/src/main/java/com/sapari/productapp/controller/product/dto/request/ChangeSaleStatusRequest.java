package com.sapari.productapp.controller.product.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.sapari.product.command.ChangeSaleStatusCommand;

/**
 * 판매 상태 전환 요청 DTO. {@code ON_SALE} ↔ {@code SUSPENDED}만 허용한다.
 *
 * @param target          전환할 상태명("ON_SALE" 또는 "SUSPENDED")
 * @param expectedVersion 상세 조회 view.version을 그대로 되돌려준다(stale-form 충돌 감지)
 */
public record ChangeSaleStatusRequest(
        @NotBlank
        @Pattern(regexp = "ON_SALE|SUSPENDED", message = "전환 상태는 ON_SALE 또는 SUSPENDED만 허용됩니다.")
        String target,
        @NotNull Long expectedVersion
) {

    /**
     * 인증된 판매자 id·대상 상품 id를 결합해 상태 전환 커맨드로 변환한다.
     *
     * @param sellerId  인증 주체(판매자) id
     * @param productId 대상 상품 id
     * @return 판매 상태 전환 커맨드
     */
    public ChangeSaleStatusCommand toCommand(UUID sellerId, UUID productId) {
        return new ChangeSaleStatusCommand(productId, sellerId, target, expectedVersion);
    }
}
