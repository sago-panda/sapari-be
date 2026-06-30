package com.sapari.productapp.controller.product.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.command.ProductOptionTypeCommand;

/**
 * 상품 등록 요청 DTO. 판매자 인증 주체(sellerId)는 본문이 아니라 {@code @CurrentUserId}로 주입되므로
 * 이 DTO에는 두지 않는다(매스 어사인먼트 방지).
 *
 * <p>이미지·추가금/제외 룰·조합별 오버라이드는 후속 증분(④·⑤)에서 추가된다 — 현재 커맨드 범위에 없다.
 *
 * @param categoryId            카테고리 id
 * @param name                  상품명
 * @param description           상세 설명 HTML(선택)
 * @param basePrice             기본 가격
 * @param shippingPolicyId      배송 정책 id, 없으면 null(판매자 기본 정책)
 * @param additionalShippingFee 상품 단독 배송비, 없으면 null(서비스가 0 처리)
 * @param tags                  상품 태그(최대 10개, 각 20자). 특수문자 규칙은 도메인 ProductTagPolicy가 강제
 * @param optionTypes           옵션 타입/값. 비어 있으면 옵션 없는 단일 상품
 * @param defaultStock          생성되는 모든 조합의 기본 재고
 */
public record CreateProductRequest(
        @NotNull Long categoryId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 50_000) String description,
        @NotNull @PositiveOrZero Integer basePrice,
        UUID shippingPolicyId,
        @PositiveOrZero Integer additionalShippingFee,
        @Size(max = 10) List<@Size(max = 20) String> tags,
        @Size(max = 30) @Valid List<OptionTypeRequest> optionTypes,
        @PositiveOrZero int defaultStock
) {

    /**
     * 인증된 판매자 id를 결합해 등록 커맨드로 변환한다.
     *
     * @param sellerId 인증 주체(판매자) id
     * @return 상품 등록 커맨드
     */
    public CreateProductCommand toCommand(UUID sellerId) {
        return new CreateProductCommand(
                sellerId,
                categoryId,
                name,
                description,
                basePrice,
                shippingPolicyId,
                additionalShippingFee,
                tags,
                toOptionTypeCommands(),
                defaultStock
        );
    }

    /**
     * 옵션 타입 요청들을 커맨드로 변환한다. null이면 옵션 없는 단일 상품이므로 빈 목록을 돌려준다.
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
