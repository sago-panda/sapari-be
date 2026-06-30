package com.sapari.productapp.controller.product.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.UpdateProductInfoCommand;

/**
 * 상품 기본 정보 수정 요청 DTO(재승인 대상). 옵션·조합은 별도 엔드포인트에서 다룬다.
 *
 * @param categoryId            변경할 카테고리 id
 * @param name                  상품명
 * @param description           상세 설명 HTML(선택)
 * @param shippingPolicyId      배송 정책 id, 없으면 null
 * @param additionalShippingFee 상품 단독 배송비, 없으면 null(서비스가 0 처리)
 * @param tags                  교체할 태그(최대 10개, 각 20자). null이면 태그 제거
 * @param expectedVersion       상세 조회 view.version을 그대로 되돌려준다(stale-form 충돌 감지)
 */
public record UpdateProductInfoRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50_000) String description,
        UUID shippingPolicyId,
        @PositiveOrZero Integer additionalShippingFee,
        @Size(max = 10) List<@Size(max = 20) String> tags,
        @NotNull Long expectedVersion
) {

    /**
     * 인증된 판매자 id·대상 상품 id를 결합해 수정 커맨드로 변환한다.
     *
     * @param sellerId  인증 주체(판매자) id
     * @param productId 수정할 상품 id
     * @return 기본 정보 수정 커맨드
     */
    public UpdateProductInfoCommand toCommand(UUID sellerId, UUID productId) {
        return new UpdateProductInfoCommand(
                productId,
                sellerId,
                categoryId,
                name,
                description,
                shippingPolicyId,
                additionalShippingFee,
                tags,
                expectedVersion
        );
    }
}
