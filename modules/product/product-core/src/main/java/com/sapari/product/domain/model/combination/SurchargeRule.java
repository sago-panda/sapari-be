package com.sapari.product.domain.model.combination;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 조합 추가금 룰. {@code valueIds}를 <b>모두</b> 포함하는 조합의 가격에 {@code amount}를 가산한다(부분조건 매칭). 옵션 저작 룰의 도메인 표현으로, 조합
 * 생성({@link CombinationGenerator})의 가격 계산에 쓰인다.
 */
public record SurchargeRule(List<UUID> valueIds, int amount) {

    public SurchargeRule {
        if (valueIds == null || valueIds.isEmpty()) {
            throw new IllegalArgumentException("surchargeRule의 valueIds는 비어 있을 수 없습니다.");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("surchargeRule의 amount는 0 이상이어야 합니다.");
        }
        valueIds = List.copyOf(valueIds);
    }

    /**
     * 조합이 보유한 옵션값 집합이 이 룰의 {@code valueIds}를 모두 포함하면 매칭이다.
     */
    boolean matches(Set<UUID> combinationValueIds) {
        return combinationValueIds.containsAll(valueIds);
    }
}
