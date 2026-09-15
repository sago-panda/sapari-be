package com.sapari.product.application.service;

import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.ExclusionRuleCommand;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.command.SurchargeRuleCommand;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.combination.SurchargeRule;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옵션 추가금·제외 룰과 조합 오버라이드(-api 커맨드)를 도메인 입력으로 조립하는 공통 헬퍼. 등록·옵션수정 유스케이스가 공유한다.
 *
 * <p>저장 전에는 옵션값 UUID가 없으므로 커맨드는 요청 내 유일한 {@code ref}로 옵션값을 가리킨다. 유스케이스는 다음 순서로 쓴다:
 * <ol>
 *   <li>{@link #validate} — 저장 전 순수 검증(잘못된 입력이면 I/O 없이 거절)
 *   <li>상품 저장 → {@link #resolveRefs}로 ref를 저장된 옵션값 id로 확정
 *   <li>{@link #toSurchargeRules}·{@link #toExclusionRules} → 조합 생성 → {@link #applyOverrides}
 *   <li>{@link #toConfig}로 룰을 jsonb 문자열로 직렬화해 config에 기록
 * </ol>
 *
 * <p>ref는 (옵션 타입명, 옵션값) 자연키로 저장본과 매칭한다. 저장본은 DB 재조회라 순서가 보장되지 않지만, 두 키는 DB 부분 유니크이고
 * {@link #validate}가 요청 내 중복을 먼저 거절하므로 안전하다.
 */
final class ProductOptionRuleAssembler {

    // 불변·스레드 안전 — 단순 record 직렬화만 하므로 공유 인스턴스로 충분하다
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProductOptionRuleAssembler() {
    }

    /**
     * 옵션 구성·룰·오버라이드를 저장 전에 검증한다. 순수 함수이며 I/O가 없다.
     *
     * <p>검증 항목: 옵션 타입명·타입 내 옵션값·ref 중복, 룰/오버라이드의 ref 존재·중복·타입당 1개, 오버라이드가 완전한 조합(타입마다 1개)인지,
     * 같은 조합 중복 오버라이드, 제외 룰에 걸리는 조합의 오버라이드, 금액·재고 음수, SKU 공백·중복.
     *
     * @param optionTypes    옵션 타입/값 커맨드
     * @param surchargeRules 추가금 룰, null이면 없음
     * @param exclusionRules 제외 룰, null이면 없음
     * @param combinations   조합 오버라이드, null이면 없음
     * @throws InvalidProductOptionException 위 규칙을 하나라도 위반한 경우
     */
    static void validate(
            List<ProductOptionTypeCommand> optionTypes,
            List<SurchargeRuleCommand> surchargeRules,
            List<ExclusionRuleCommand> exclusionRules,
            List<CombinationOverrideCommand> combinations) {
        List<ProductOptionTypeCommand> types = nullToEmpty(optionTypes);
        // ref → 소속 옵션 타입명 (룰이 한 타입의 값을 두 개 가리키는지 판정하는 기준)
        Map<String, String> refToType = indexRefs(types);

        for (SurchargeRuleCommand rule : nullToEmpty(surchargeRules)) {
            if (rule.amount() < 0) {
                throw new InvalidProductOptionException("추가금 룰 금액은 0 이상이어야 합니다: " + rule.amount());
            }
            requireRefs(rule.valueRefs(), refToType, "추가금 룰");
        }

        // 제외 룰의 ref 집합 — 오버라이드가 제외된 조합을 가리키는지 판정할 때 재사용
        List<Set<String>> exclusionRefSets = new ArrayList<>();
        for (ExclusionRuleCommand rule : nullToEmpty(exclusionRules)) {
            exclusionRefSets.add(requireRefs(rule.valueRefs(), refToType, "제외 룰"));
        }

        Set<Set<String>> overriddenCombinations = new HashSet<>();
        Set<String> skus = new HashSet<>();
        for (CombinationOverrideCommand override : nullToEmpty(combinations)) {
            Set<String> refs = requireRefs(override.valueRefs(), refToType, "조합 오버라이드");
            // 타입당 1개가 이미 보장되므로, 개수가 타입 수와 같으면 완전한 조합 하나를 가리킨다
            if (refs.size() != types.size()) {
                throw new InvalidProductOptionException(
                        "조합 오버라이드는 옵션 타입마다 값을 하나씩 지정해야 합니다: " + override.valueRefs());
            }
            if (!overriddenCombinations.add(refs)) {
                throw new InvalidProductOptionException("같은 조합을 중복 오버라이드했습니다: " + override.valueRefs());
            }
            // 제외 룰은 부분조건 매칭이므로, 룰의 ref를 모두 포함하는 조합은 생성되지 않는다 → 오버라이드 대상이 없다
            if (exclusionRefSets.stream().anyMatch(refs::containsAll)) {
                throw new InvalidProductOptionException("제외된 조합은 오버라이드할 수 없습니다: " + override.valueRefs());
            }
            requireNonNegative(override.price(), "판매가");
            requireNonNegative(override.originalPrice(), "정가");
            requireNonNegative(override.stock(), "재고");
            if (override.sku() != null) {
                if (override.sku().isBlank()) {
                    throw new InvalidProductOptionException("SKU는 비어 있을 수 없습니다.");
                }
                // DB 유니크 (product_id, sku) 위반을 서버 오류가 아닌 입력 오류로 먼저 거절한다
                if (!skus.add(override.sku())) {
                    throw new InvalidProductOptionException("상품 안에서 SKU가 중복됩니다: " + override.sku());
                }
            }
        }
    }

    /**
     * 커맨드의 ref를 저장된 옵션값 id로 확정한다. ref가 없는 옵션값은 결과에 포함하지 않는다.
     *
     * @param optionTypes 저장에 사용한 옵션 타입/값 커맨드({@link #validate} 통과분)
     * @param savedTypes  저장 후 돌려받은 옵션 타입(값 id 부여됨)
     * @return ref → 옵션값 id
     */
    static Map<String, UUID> resolveRefs(
            List<ProductOptionTypeCommand> optionTypes,
            List<ProductOptionTypeModel> savedTypes) {
        // (타입명 → (옵션값 → id)) 색인 — 저장본 순서에 의존하지 않고 자연키로 찾는다
        Map<String, Map<String, UUID>> savedIds = savedTypes.stream()
                .collect(Collectors.toMap(
                        ProductOptionTypeModel::name,
                        type -> type.values()
                                .stream()
                                .collect(Collectors.toMap(ProductOptionValueModel::value, ProductOptionValueModel::id))));

        Map<String, UUID> refToId = new HashMap<>();
        for (ProductOptionTypeCommand type : nullToEmpty(optionTypes)) {
            for (ProductOptionValueCommand value : nullToEmpty(type.values())) {
                if (value.ref() != null) {
                    refToId.put(value.ref(), savedIds.get(type.name()).get(value.value()));
                }
            }
        }
        return refToId;
    }

    /**
     * 추가금 룰 커맨드를 옵션값 id 기반 도메인 룰로 변환한다. null이면 빈 목록.
     *
     * @param commands 추가금 룰 커맨드
     * @param refToId  {@link #resolveRefs} 결과
     * @return 도메인 추가금 룰
     */
    static List<SurchargeRule> toSurchargeRules(List<SurchargeRuleCommand> commands, Map<String, UUID> refToId) {
        return nullToEmpty(commands).stream()
                .map(rule -> new SurchargeRule(toValueIds(rule.valueRefs(), refToId), rule.amount()))
                .toList();
    }

    /**
     * 제외 룰 커맨드를 옵션값 id 기반 도메인 룰로 변환한다. null이면 빈 목록.
     *
     * @param commands 제외 룰 커맨드
     * @param refToId  {@link #resolveRefs} 결과
     * @return 도메인 제외 룰
     */
    static List<ExclusionRule> toExclusionRules(List<ExclusionRuleCommand> commands, Map<String, UUID> refToId) {
        return nullToEmpty(commands).stream()
                .map(rule -> new ExclusionRule(toValueIds(rule.valueRefs(), refToId), rule.reason()))
                .toList();
    }

    /**
     * 생성된 조합에 오버라이드를 적용한다. 오버라이드 필드가 null이면 판매가·재고는 생성값을 유지하고, 정가·SKU는 미설정으로 둔다.
     *
     * @param generated 조합 생성기 결과
     * @param overrides 조합 오버라이드 커맨드({@link #validate} 통과분), null이면 없음
     * @param refToId   {@link #resolveRefs} 결과
     * @return 오버라이드가 반영된 조합 목록(순서 유지)
     */
    static List<ProductOptionCombination> applyOverrides(
            List<ProductOptionCombination> generated,
            List<CombinationOverrideCommand> overrides,
            Map<String, UUID> refToId) {
        // 옵션값 id 집합으로 색인 — 조합 키 규칙(정렬·join)을 여기서 다시 구현하지 않고 조합의 optionValueIds와 직접 대조한다
        Map<Set<UUID>, CombinationOverrideCommand> byValueIds = nullToEmpty(overrides).stream()
                .collect(Collectors.toMap(o -> Set.copyOf(toValueIds(o.valueRefs(), refToId)), o -> o));

        return generated.stream()
                .map(combination -> {
                    CombinationOverrideCommand override = byValueIds.get(Set.copyOf(combination.optionValueIds()));
                    return override == null ? combination : applyOverride(combination, override);
                })
                .toList();
    }

    /**
     * 룰을 config jsonb 문자열로 직렬화한다({@code [{"valueIds":[...],"amount":..}]} / {@code [{"valueIds":[...],"reason":..}]}).
     * 둘 다 비어 있으면 null(config 행 없음), 한쪽만 비면 그쪽 컬럼은 null이다.
     *
     * @param surchargeRules 도메인 추가금 룰
     * @param exclusionRules 도메인 제외 룰
     * @return 옵션 config, 룰이 하나도 없으면 null
     */
    static ProductOptionConfigModel toConfig(List<SurchargeRule> surchargeRules, List<ExclusionRule> exclusionRules) {
        if (surchargeRules.isEmpty() && exclusionRules.isEmpty()) {
            return null;
        }
        return ProductOptionConfigModel.of(toJsonOrNull(surchargeRules), toJsonOrNull(exclusionRules));
    }

    /**
     * 옵션값 커맨드의 ref를 모아 소속 타입명으로 색인하고, 타입명·타입 내 옵션값·ref 중복을 거절한다.
     */
    private static Map<String, String> indexRefs(List<ProductOptionTypeCommand> types) {
        Set<String> typeNames = new HashSet<>();
        Map<String, String> refToType = new HashMap<>();
        for (ProductOptionTypeCommand type : types) {
            // ref 해석이 (타입명, 옵션값) 자연키에 기대므로 요청 안에서도 유일해야 한다 (DB 부분 유니크와 동일 규칙)
            if (!typeNames.add(type.name())) {
                throw new InvalidProductOptionException("옵션 타입명이 중복됩니다: " + type.name());
            }
            Set<String> values = new HashSet<>();
            for (ProductOptionValueCommand value : nullToEmpty(type.values())) {
                if (!values.add(value.value())) {
                    throw new InvalidProductOptionException(
                            "옵션 타입 '" + type.name() + "' 안에서 옵션값이 중복됩니다: " + value.value());
                }
                if (value.ref() == null) {
                    continue;
                }
                if (value.ref().isBlank()) {
                    throw new InvalidProductOptionException("옵션값 ref는 비어 있을 수 없습니다: " + value.value());
                }
                if (refToType.putIfAbsent(value.ref(), type.name()) != null) {
                    throw new InvalidProductOptionException("옵션값 ref가 중복됩니다: " + value.ref());
                }
            }
        }
        return refToType;
    }

    /**
     * 룰/오버라이드의 ref 목록을 검증한다 — 비어 있지 않고, 모두 존재하며, 중복이 없고, 한 옵션 타입의 값을 두 개 이상 가리키지 않아야 한다.
     *
     * @return 검증된 ref 집합
     */
    private static Set<String> requireRefs(List<String> valueRefs, Map<String, String> refToType, String subject) {
        if (valueRefs == null || valueRefs.isEmpty()) {
            throw new InvalidProductOptionException(subject + "의 옵션값 ref가 비어 있습니다.");
        }
        Set<String> refs = new HashSet<>();
        Set<String> types = new HashSet<>();
        for (String ref : valueRefs) {
            String typeName = refToType.get(ref);
            if (typeName == null) {
                throw new InvalidProductOptionException(subject + "가 존재하지 않는 옵션값 ref를 가리킵니다: " + ref);
            }
            if (!refs.add(ref)) {
                throw new InvalidProductOptionException(subject + "에 옵션값 ref가 중복됩니다: " + ref);
            }
            // 조합은 타입마다 값 하나만 가지므로, 같은 타입 값 두 개를 요구하는 조건은 어떤 조합과도 매칭될 수 없다
            if (!types.add(typeName)) {
                throw new InvalidProductOptionException(
                        subject + "가 옵션 타입 '" + typeName + "'의 값을 두 개 이상 가리킵니다: " + valueRefs);
            }
        }
        return refs;
    }

    /**
     * 값이 있으면 0 이상인지 검증한다(null은 미지정이라 통과).
     */
    private static void requireNonNegative(Integer value, String field) {
        if (value != null && value < 0) {
            throw new InvalidProductOptionException("조합 오버라이드 " + field + "는 0 이상이어야 합니다: " + value);
        }
    }

    /**
     * 조합 하나에 오버라이드 필드를 반영한다.
     */
    private static ProductOptionCombination applyOverride(
            ProductOptionCombination combination,
            CombinationOverrideCommand override) {
        return combination.toBuilder()
                .price(override.price() != null ? override.price() : combination.price())
                .originalPrice(override.originalPrice())
                // 신규 조합이라 예약 재고는 없다
                .stock(override.stock() != null ? Stock.of(override.stock(), 0) : combination.stock())
                .sku(Sku.of(override.sku()))
                .build();
    }

    /**
     * ref 목록을 옵션값 id 목록으로 변환한다(순서 유지).
     */
    private static List<UUID> toValueIds(List<String> valueRefs, Map<String, UUID> refToId) {
        return valueRefs.stream()
                .map(refToId::get)
                .toList();
    }

    /**
     * 비어 있으면 null, 아니면 JSON 배열 문자열.
     */
    private static String toJsonOrNull(List<?> rules) {
        return rules.isEmpty() ? null : JSON.writeValueAsString(rules);
    }

    /**
     * null 목록을 빈 목록으로 정규화한다.
     */
    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
