package com.sapari.product.infrastructure.persistence.mapper.product;

import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.SurchargeRule;
import java.util.List;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옵션 config의 룰 목록 ↔ jsonb 문자열 변환. 저장 포맷은 영속 계층의 결정이므로 매퍼만 이 코덱을 쓴다.
 *
 * <p>포맷: 추가금 {@code [{"valueIds":[...],"amount":..}]}, 제외 {@code [{"valueIds":[...],"reason":..}]}. 빈 목록은 컬럼 null로 저장하고,
 * null 컬럼은 빈 목록으로 읽는다.
 */
final class OptionRuleJsonCodec {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<SurchargeRule>> SURCHARGE_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<ExclusionRule>> EXCLUSION_LIST = new TypeReference<>() {
    };

    private OptionRuleJsonCodec() {
    }

    /**
     * 룰 목록을 JSON 배열 문자열로 바꾼다. 비어 있으면 null(컬럼 비움).
     *
     * @param rules 추가금 또는 제외 룰
     * @return JSON 배열 문자열, 빈 목록이면 null
     */
    static String write(List<?> rules) {
        return rules.isEmpty() ? null : JSON.writeValueAsString(rules);
    }

    /**
     * 추가금 룰 컬럼을 읽는다.
     *
     * @param json jsonb 문자열, 없으면 null
     * @return 추가금 룰(없으면 빈 목록)
     */
    static List<SurchargeRule> readSurcharges(String json) {
        return json == null ? List.of() : JSON.readValue(json, SURCHARGE_LIST);
    }

    /**
     * 제외 룰 컬럼을 읽는다.
     *
     * @param json jsonb 문자열, 없으면 null
     * @return 제외 룰(없으면 빈 목록)
     */
    static List<ExclusionRule> readExclusions(String json) {
        return json == null ? List.of() : JSON.readValue(json, EXCLUSION_LIST);
    }
}
