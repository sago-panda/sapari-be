package com.sapari.product.view;

import java.util.List;
import java.util.UUID;

/**
 * 옵션 조합 상세 뷰. 재고·가격 단위.
 *
 * @param combinationId  조합 id
 * @param combinationKey 옵션값 id 오름차순 조합 문자열
 * @param optionValueIds 이 조합을 이루는 옵션값 id들
 * @param price          판매가
 * @param originalPrice  비교 정가(취소선), 없으면 null
 * @param stock          총 재고
 * @param availableStock 가용 재고(stock - 예약)
 * @param isAvailable    판매 가능 여부(false=단종)
 * @param sku            SKU 코드, 없으면 null
 * @param version        낙관적 락 버전. 조합별 수정 시 expectedVersion으로 되돌려준다(stale-form 차단)
 */
public record CombinationView(
        UUID combinationId,
        String combinationKey,
        List<UUID> optionValueIds,
        Integer price,
        Integer originalPrice,
        Integer stock,
        Integer availableStock,
        Boolean isAvailable,
        String sku,
        Long version
) {
}
