package com.sapari.product.domain.model.combination;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 불가 조합 룰. {@code valueIds}를 <b>모두</b> 포함하는 조합은 생성하지 않는다(행 미생성). 옵션 저작 룰의 도메인 표현으로, 조합
 * 생성({@link CombinationGenerator})의 제외 판정에 쓰인다.
 */
public record ExclusionRule(List<UUID> valueIds) {

    public ExclusionRule {
        if (valueIds == null || valueIds.isEmpty()) {
            throw new IllegalArgumentException("exclusionRule의 valueIds는 비어 있을 수 없습니다.");
        }
        valueIds = List.copyOf(valueIds);
    }

    /**
     * 조합이 보유한 옵션값 집합이 이 룰의 {@code valueIds}를 모두 포함하면 매칭(제외 대상)이다.
     */
    boolean matches(Set<UUID> combinationValueIds) {
        return combinationValueIds.containsAll(valueIds);
    }
}
