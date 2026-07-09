package com.sapari.product.command;

import java.util.UUID;

/**
 * 단일 조합의 가격·재고·판매여부 변경 입력. null 필드는 "변경 안 함"을 의미한다.
 *
 * @param combinationId 변경할 조합 id
 * @param price         새 판매가, null이면 가격 변경 없음
 * @param originalPrice 새 비교 정가(취소선). price가 지정될 때 함께 적용되며, null이면 정가 제거
 * @param stock         새 총 재고, null이면 재고 변경 없음
 * @param isAvailable   판매 가능 여부, null이면 변경 없음(true=재개, false=단종)
 * @param expectedVersion 클라이언트가 본 조합 version(조합별 stale-form 충돌 감지용)
 */
public record CombinationUpdateCommand(
        UUID combinationId,
        Integer price,
        Integer originalPrice,
        Integer stock,
        Boolean isAvailable,
        Long expectedVersion
) {
}
