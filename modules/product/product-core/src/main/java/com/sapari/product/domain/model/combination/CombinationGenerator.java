package com.sapari.product.domain.model.combination;

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsLast;
import static java.util.stream.Collectors.joining;

import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.TooManyCombinationsException;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 옵션 타입/값과 저작 룰(추가금·제외)로부터 옵션 조합을 만들어내는 순수 도메인 서비스.
 *
 * <p>인프라 의존이 없으며, 입력 옵션값은 이미 영속화되어 id가 부여된 상태여야 한다(조합 키·매핑이 옵션값 id를 참조).
 * 가격은 {@code basePrice + Σ(옵션값 priceDelta) + Σ(매칭 surcharge.amount)}로 산출하고, 제외 룰에 걸리는 조합은 생성하지 않으며, 조합 수가 한도를 넘으면 예외를
 * 던진다. 현재 {@code COMBINATION} 옵션 모델만 지원한다.
 */
public final class CombinationGenerator {

    /**
     * 상품당 옵션 조합 수 상한 (DDL 주석 기준).
     */
    public static final int MAX_COMBINATIONS = 500;

    private CombinationGenerator() {
    }

    /**
     * 옵션 타입들의 데카르트 곱으로 조합을 생성한다. 옵션 타입이 없으면 빈 목록(옵션 없는 단일 상품)을 반환한다. 생성된 조합은 {@code id=null}, {@code isAvailable=true},
     * 재고는 {@code defaultStock}(예약 0)으로 시작한다.
     *
     * @param productId      소속 상품 id (영속화되어 부여된 값)
     * @param basePrice      상품 기본 가격
     * @param optionTypes    옵션 타입 목록(각 값은 id가 부여된 상태). 타입·값은 sortOrder 순으로 정렬해 처리한다
     * @param surchargeRules 추가금 룰. null이면 없음으로 처리
     * @param exclusionRules 제외 룰. null이면 없음으로 처리
     * @param defaultStock   생성 조합의 기본 재고
     * @param now            생성 시각 (호출측이 TimeProvider로 구해 전달)
     * @throws InvalidProductOptionException 값이 하나도 없는 옵션 타입이 있는 경우
     * @throws TooManyCombinationsException  조합 수가 {@link #MAX_COMBINATIONS}를 초과하는 경우
     */
    public static List<ProductOptionCombination> generate(
            UUID productId,
            int basePrice,
            List<ProductOptionTypeModel> optionTypes,
            List<SurchargeRule> surchargeRules,
            List<ExclusionRule> exclusionRules,
            int defaultStock,
            Instant now) {
        // 옵션 타입이 없으면 조합도 없다 (옵션 없는 단일 가격·재고 상품)
        if (optionTypes == null || optionTypes.isEmpty()) {
            return List.of();
        }

        // 타입을 sortOrder 순으로 고정 — 데카르트 곱의 결합 순서를 결정적으로 만든다
        List<ProductOptionTypeModel> sortedTypes = optionTypes.stream()
                .sorted(comparing(ProductOptionTypeModel::sortOrder, nullsLast(naturalOrder())))
                .toList();

        // 값 없는 타입·조합 수 초과를 데카르트 곱을 펼치기 전에 미리 차단
        guardCardinality(sortedTypes);

        // null 룰은 빈 목록으로 정규화 — 이후 스트림에서 null 분기를 없앤다
        List<SurchargeRule> surcharges = surchargeRules == null ? List.of() : surchargeRules;
        List<ExclusionRule> exclusions = exclusionRules == null ? List.of() : exclusionRules;

        List<ProductOptionCombination> result = new ArrayList<>();
        for (List<ProductOptionValueModel> tuple : cartesian(sortedTypes)) {
            // 이 조합을 이루는 옵션값 id 묶음 (매칭·키 산출의 기준)
            List<UUID> valueIds = tuple.stream()
                    .map(ProductOptionValueModel::id)
                    .toList();
            Set<UUID> idSet = Set.copyOf(valueIds);

            // 제외 룰에 걸리는 조합은 행을 만들지 않는다 (DDL: 매칭 조합 미생성)
            if (exclusions.stream()
                    .anyMatch(rule -> rule.matches(idSet))) {
                continue;
            }

            // 가격 = 기본가 + Σ(옵션값 추가금) + Σ(이 조합에 매칭되는 추가금 룰 amount)
            int price = basePrice
                    + tuple.stream()
                    .mapToInt(ProductOptionValueModel::priceDelta)
                    .sum()
                    + surcharges.stream()
                    .filter(rule -> rule.matches(idSet))
                    .mapToInt(SurchargeRule::amount)
                    .sum();

            // 신규 조합 — sku·originalPrice 미지정, 재고는 defaultStock(예약 0)으로 시작
            result.add(ProductOptionCombination.create(
                    productId,
                    CombinationKey.of(combinationKey(valueIds)),
                    null,
                    null,
                    price,
                    Stock.of(defaultStock, 0),
                    valueIds,
                    now));
        }
        return result;
    }

    /**
     * 각 타입에 값이 있는지 검증하고, 데카르트 곱 카디널리티가 한도를 넘으면 미리 예외를 던진다(거대 목록 생성 방지).
     */
    private static void guardCardinality(List<ProductOptionTypeModel> types) {
        // 조합 수 = 타입별 값 개수의 곱. 곱을 누적하며 한도 초과를 조기에 감지한다 (long으로 오버플로 방지)
        long cardinality = 1L;
        for (ProductOptionTypeModel type : types) {
            // 값이 하나도 없는 타입은 조합을 이룰 수 없다 → 옵션 구성 오류
            if (type.values()
                    .isEmpty()) {
                throw new InvalidProductOptionException("옵션 타입 '" + type.name() + "'에 값이 없습니다.");
            }
            cardinality *= type.values()
                    .size();
            // 한도 초과 즉시 중단 — 거대한 데카르트 곱을 메모리에 펼치기 전에 막는다
            if (cardinality > MAX_COMBINATIONS) {
                throw new TooManyCombinationsException(
                        "조합 수가 한도(" + MAX_COMBINATIONS + ")를 초과합니다.");
            }
        }
    }

    /**
     * 옵션 타입별 값 목록의 데카르트 곱을 만든다. 각 타입의 값은 sortOrder 순으로 정렬해 결합한다.
     */
    private static List<List<ProductOptionValueModel>> cartesian(List<ProductOptionTypeModel> types) {
        // 빈 조합 1개에서 시작해 타입을 하나씩 곱해 나간다 (점진적 데카르트 곱)
        List<List<ProductOptionValueModel>> acc = new ArrayList<>();
        acc.add(new ArrayList<>());
        for (ProductOptionTypeModel type : types) {
            // 같은 타입 안에서도 값은 sortOrder 순으로 — 결과 순서를 결정적으로 유지
            List<ProductOptionValueModel> sortedValues = type.values()
                    .stream()
                    .sorted(comparing(ProductOptionValueModel::sortOrder, nullsLast(naturalOrder())))
                    .toList();
            // 기존 부분조합 각각에 이 타입의 값들을 하나씩 덧붙여 새 부분조합 집합을 만든다
            List<List<ProductOptionValueModel>> next = new ArrayList<>();
            for (List<ProductOptionValueModel> prefix : acc) {
                for (ProductOptionValueModel value : sortedValues) {
                    List<ProductOptionValueModel> combination = new ArrayList<>(prefix);
                    combination.add(value);
                    next.add(combination);
                }
            }
            acc = next;
        }
        return acc;
    }

    /**
     * 조합 키를 만든다 — 옵션값 id를 오름차순 정렬해 {@code ':'}로 join한다 (DDL combination_key 규칙).
     */
    private static String combinationKey(List<UUID> valueIds) {
        return valueIds.stream()
                .sorted()
                .map(UUID::toString)
                .collect(joining(":"));
    }
}
