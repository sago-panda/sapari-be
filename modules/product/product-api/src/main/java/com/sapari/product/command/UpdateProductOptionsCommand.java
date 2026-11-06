package com.sapari.product.command;

import java.util.List;
import java.util.UUID;

/**
 * 상품 옵션 수정 입력. 옵션 타입/값을 통째로 교체하고 조합을 재생성한다.
 *
 * <p>옵션 타입/값은 새 것으로 교체되어(delete-replace) 새 id를 받는다. 기존 조합은 주문이 id로 참조하므로 삭제하지 않고 단종 처리한 뒤
 * 새 조합으로 대체된다.
 * 추가금/제외 룰·조합별 오버라이드도 새 옵션값 기준으로 통째로 교체된다.
 *
 * @param productId    수정할 상품 id
 * @param sellerId     요청 판매자 id(소유권 확인용)
 * @param optionTypes     교체할 옵션 타입/값
 * @param defaultStock    재생성되는 모든 조합의 기본 재고
 * @param expectedVersion 클라이언트가 수정 폼에서 본 상품 version(stale-form 충돌 감지용)
 * @param surchargeRules  조합 추가금 룰, 없으면 null 또는 빈 목록
 * @param exclusionRules  불가 조합 룰, 없으면 null 또는 빈 목록
 * @param combinations    조합별 오버라이드(가격·정가·재고·SKU), 없으면 null 또는 빈 목록
 */
public record UpdateProductOptionsCommand(
        UUID productId,
        UUID sellerId,
        List<ProductOptionTypeCommand> optionTypes,
        int defaultStock,
        Long expectedVersion,
        List<SurchargeRuleCommand> surchargeRules,
        List<ExclusionRuleCommand> exclusionRules,
        List<CombinationOverrideCommand> combinations
) {
}
