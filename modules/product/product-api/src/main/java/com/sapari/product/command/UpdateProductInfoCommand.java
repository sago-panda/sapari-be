package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 상품 기본 정보 수정 입력(재승인 대상). 이름·설명·카테고리·배송·태그를 교체한다.
 *
 * <p>옵션·조합은 별도 유스케이스에서 다룬다. 이미지는 후속 증분에서 추가한다.
 *
 * @param productId             수정할 상품 id
 * @param sellerId              요청 판매자 id(소유권 확인용)
 * @param categoryId            변경할 카테고리 id
 * @param name                  상품명
 * @param description           상세 설명
 * @param shippingPolicyId      배송 정책 id, 없으면 null
 * @param additionalShippingFee 상품 단독 배송비, 없으면 0
 * @param tags                  교체할 태그 목록(최대 10개, 각 20자, 특수문자 불가). null이면 태그 제거
 */
public record UpdateProductInfoCommand(
        UUID productId,
        UUID sellerId,
        Long categoryId,
        String name,
        String description,
        UUID shippingPolicyId,
        Integer additionalShippingFee,
        List<String> tags
) {
}
