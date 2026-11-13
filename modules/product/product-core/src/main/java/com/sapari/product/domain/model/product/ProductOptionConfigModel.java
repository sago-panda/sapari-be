package com.sapari.product.domain.model.product;

import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.SurchargeRule;
import java.util.List;

/**
 * 상품별 옵션 저작 룰 (조합 추가금 + 불가 조합). 저장 포맷(jsonb)은 영속 매퍼가 정하고, 도메인은 룰 목록으로 다룬다.
 *
 * @param surchargeRules 추가금 룰(null이면 빈 목록)
 * @param exclusionRules 제외 룰(null이면 빈 목록)
 */
public record ProductOptionConfigModel(
        List<SurchargeRule> surchargeRules,
        List<ExclusionRule> exclusionRules
) {

    /**
     * null 목록을 빈 목록으로 정규화하고 방어적으로 복사한다.
     */
    public ProductOptionConfigModel {
        surchargeRules = surchargeRules == null ? List.of() : List.copyOf(surchargeRules);
        exclusionRules = exclusionRules == null ? List.of() : List.copyOf(exclusionRules);
    }

    /**
     * 룰 목록으로 config를 만든다.
     *
     * @param surchargeRules 추가금 룰
     * @param exclusionRules 제외 룰
     * @return 옵션 config
     */
    public static ProductOptionConfigModel of(List<SurchargeRule> surchargeRules, List<ExclusionRule> exclusionRules) {
        return new ProductOptionConfigModel(surchargeRules, exclusionRules);
    }
}
