package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 상품 등록 입력. 상품 기본 정보 + 태그 + 옵션 타입/값 + 조합 기본 재고를 담는다.
 *
 * <p>도메인에 의존하지 않는 표현 DTO다. 이미지·추가금/제외 룰·조합별 오버라이드는 후속 증분에서 추가한다.
 *
 * @param sellerId              판매자 id(컨트롤러의 인증 주체)
 * @param categoryId            카테고리 id
 * @param name                  상품명
 * @param description           상세 설명
 * @param basePrice             기본 가격
 * @param shippingPolicyId      배송 정책 id, 없으면 null(판매자 기본 정책)
 * @param additionalShippingFee 상품 단독 배송비, 없으면 0
 * @param tags                  상품 태그(최대 10개, 각 20자, 특수문자 불가)
 * @param optionTypes           옵션 타입/값. 비어 있으면 옵션 없는 단일 상품
 * @param defaultStock          생성되는 모든 조합의 기본 재고
 */
public record CreateProductCommand(
        UUID sellerId,
        Long categoryId,
        String name,
        String description,
        Integer basePrice,
        UUID shippingPolicyId,
        Integer additionalShippingFee,
        List<String> tags,
        List<ProductOptionTypeCommand> optionTypes,
        int defaultStock
) {
}
