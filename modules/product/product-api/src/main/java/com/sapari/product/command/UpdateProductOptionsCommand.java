package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 상품 옵션 수정 입력. 옵션 타입/값을 통째로 교체하고 조합을 재생성한다.
 *
 * <p>기존 옵션값은 주문 이력 보존을 위해 물리 삭제하지 않으며, 기존 조합은 단종 처리 후 새 조합으로 대체된다.
 * 추가금/제외 룰·조합별 오버라이드는 후속 증분에서 추가한다.
 *
 * @param productId    수정할 상품 id
 * @param sellerId     요청 판매자 id(소유권 확인용)
 * @param optionTypes     교체할 옵션 타입/값
 * @param defaultStock    재생성되는 모든 조합의 기본 재고
 * @param expectedVersion 클라이언트가 수정 폼에서 본 상품 version(stale-form 충돌 감지용)
 */
public record UpdateProductOptionsCommand(
        UUID productId,
        UUID sellerId,
        List<ProductOptionTypeCommand> optionTypes,
        int defaultStock,
        Long expectedVersion
) {
}
