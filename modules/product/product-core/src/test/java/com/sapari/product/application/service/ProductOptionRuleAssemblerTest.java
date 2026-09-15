package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.ExclusionRuleCommand;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.command.SurchargeRuleCommand;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.ProductErrorCode;
import com.sapari.product.domain.model.combination.CombinationGenerator;
import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.SurchargeRule;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("ProductOptionRuleAssembler")
class ProductOptionRuleAssemblerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID RED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLUE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID S = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID M = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");
    private static final int BASE_PRICE = 19_900;
    private static final int DEFAULT_STOCK = 100;
    private static final Map<String, UUID> REF_TO_ID = Map.of("c-red", RED, "c-blue", BLUE, "s-s", S, "s-m", M);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ProductOptionValueCommand value(String value, String ref) {
        return new ProductOptionValueCommand(value, null, null, 0, (short) 1, ref);
    }

    private static ProductOptionTypeCommand type(String name, ProductOptionValueCommand... values) {
        return new ProductOptionTypeCommand(name, null, (short) 1, List.of(values));
    }

    /** 색상(빨강 c-red · 파랑 c-blue) × 사이즈(S s-s · M s-m) 옵션 커맨드. */
    private static List<ProductOptionTypeCommand> colorAndSize() {
        return List.of(
                type("색상", value("빨강", "c-red"), value("파랑", "c-blue")),
                type("사이즈", value("S", "s-s"), value("M", "s-m")));
    }

    /** 가격·정가·재고·SKU를 모두 비운(기본값 유지) 조합 오버라이드. */
    private static CombinationOverrideCommand override(String... refs) {
        return new CombinationOverrideCommand(List.of(refs), null, null, null, null);
    }

    /** {@link #colorAndSize()}를 저장했을 때 돌아오는 저장본(값 id 부여). 순서 비의존을 확인하려 타입·값 순서를 뒤집어 둔다. */
    private static List<ProductOptionTypeModel> savedColorAndSizeReversed() {
        ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 2, List.of(
                new ProductOptionValueModel(M, null, "M", null, 0, (short) 2),
                new ProductOptionValueModel(S, null, "S", null, 0, (short) 1)));
        ProductOptionTypeModel color = new ProductOptionTypeModel(UUID.randomUUID(), null, "색상", (short) 1, List.of(
                new ProductOptionValueModel(BLUE, null, "파랑", null, 0, (short) 2),
                new ProductOptionValueModel(RED, null, "빨강", null, 0, (short) 1)));
        return List.of(size, color);
    }

    /** 저장본으로 룰 없이 생성한 조합 4개(빨강S·빨강M·파랑S·파랑M). */
    private static List<ProductOptionCombination> generatedCombinations() {
        return CombinationGenerator.generate(
                PRODUCT_ID, BASE_PRICE, savedColorAndSizeReversed(), List.of(), List.of(), DEFAULT_STOCK, NOW);
    }

    private static ProductOptionCombination combinationOf(List<ProductOptionCombination> combinations, UUID... ids) {
        return combinations.stream()
                .filter(c -> Set.copyOf(c.optionValueIds()).equals(Set.of(ids)))
                .findFirst()
                .orElseThrow();
    }

    @Nested
    @DisplayName("validate — 통과")
    class ValidateAccepts {

        @Test
        @DisplayName("ref로 연결된 추가금·제외 룰과 완전한 조합 오버라이드는 통과한다")
        void acceptsWellFormedInput() {
            assertThatCode(() -> ProductOptionRuleAssembler.validate(
                    colorAndSize(),
                    List.of(new SurchargeRuleCommand(List.of("c-red", "s-m"), 500)),
                    List.of(new ExclusionRuleCommand(List.of("c-blue", "s-s"), "미생산")),
                    List.of(new CombinationOverrideCommand(List.of("c-red", "s-m"), 18_900, 22_000, 30, "TS-RED-M"))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("룰·오버라이드가 모두 null이면 통과한다 (룰 없는 기존 요청 호환)")
        void acceptsNullRules() {
            assertThatCode(() -> ProductOptionRuleAssembler.validate(colorAndSize(), null, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("옵션도 룰도 없으면 통과한다 (옵션 없는 단일 상품)")
        void acceptsNoOptionsAtAll() {
            assertThatCode(() -> ProductOptionRuleAssembler.validate(null, List.of(), List.of(), List.of()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 타입끼리는 같은 옵션값 이름을 쓸 수 있고, 참조되지 않는 옵션값은 ref가 없어도 된다")
        void acceptsSameValueNameAcrossTypesAndMissingRefs() {
            List<ProductOptionTypeCommand> types = List.of(
                    type("상의", value("FREE", "top-free")),
                    type("하의", value("FREE", null)));

            assertThatCode(() -> ProductOptionRuleAssembler.validate(types, null, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("경계값: 금액·판매가·정가·재고 0 허용, SKU 미지정(null) 오버라이드는 여러 개여도 중복이 아니다")
        void acceptsZeroAndMultipleNullSkus() {
            assertThatCode(() -> ProductOptionRuleAssembler.validate(
                    colorAndSize(),
                    List.of(new SurchargeRuleCommand(List.of("c-red"), 0)),
                    null,
                    List.of(
                            new CombinationOverrideCommand(List.of("c-red", "s-s"), 0, 0, 0, null),
                            new CombinationOverrideCommand(List.of("c-red", "s-m"), 0, 0, 0, null))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("제외 룰과 일부만 겹치는 조합(제외 조건을 다 포함하지 않음)은 오버라이드할 수 있다")
        void acceptsOverrideNotCoveredByExclusion() {
            assertThatCode(() -> ProductOptionRuleAssembler.validate(
                    colorAndSize(),
                    null,
                    List.of(new ExclusionRuleCommand(List.of("c-blue", "s-s"), null)),
                    List.of(override("c-blue", "s-m"), override("c-red", "s-s"))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("validate — 거절 (InvalidProductOptionException, PRODUCT-004)")
    class ValidateRejects {

        static Stream<Arguments> invalidInputs() {
            List<ProductOptionTypeCommand> ok = colorAndSize();
            return Stream.of(
                    argumentSet("옵션 타입명 중복",
                            List.of(type("색상", value("빨강", null)), type("색상", value("파랑", null))),
                            null, null, null),
                    argumentSet("한 타입 안에서 옵션값 중복",
                            List.of(type("색상", value("빨강", null), value("빨강", null))),
                            null, null, null),
                    argumentSet("ref 중복 (다른 타입 사이에서도)",
                            List.of(type("색상", value("빨강", "x")), type("사이즈", value("S", "x"))),
                            null, null, null),
                    argumentSet("ref 공백",
                            List.of(type("색상", value("빨강", " "))),
                            null, null, null),
                    argumentSet("추가금 룰 valueRefs null",
                            ok, List.of(new SurchargeRuleCommand(null, 100)), null, null),
                    argumentSet("추가금 룰 valueRefs 빈 목록",
                            ok, List.of(new SurchargeRuleCommand(List.of(), 100)), null, null),
                    argumentSet("추가금 룰 금액 음수",
                            ok, List.of(new SurchargeRuleCommand(List.of("c-red"), -1)), null, null),
                    argumentSet("추가금 룰이 존재하지 않는 ref 참조",
                            ok, List.of(new SurchargeRuleCommand(List.of("c-green"), 100)), null, null),
                    argumentSet("제외 룰에 같은 ref 중복",
                            ok, null, List.of(new ExclusionRuleCommand(List.of("c-red", "c-red"), null)), null),
                    argumentSet("제외 룰이 한 타입의 값을 두 개 참조 (어떤 조합과도 매칭 불가)",
                            ok, null, List.of(new ExclusionRuleCommand(List.of("c-red", "c-blue"), null)), null),
                    argumentSet("오버라이드가 타입 하나만 지정 (불완전 조합)",
                            ok, null, null, List.of(override("c-red"))),
                    argumentSet("오버라이드가 존재하지 않는 ref 참조",
                            ok, null, null, List.of(override("c-red", "s-xl"))),
                    argumentSet("같은 조합 중복 오버라이드 (ref 순서만 다름)",
                            ok, null, null, List.of(override("c-red", "s-m"), override("s-m", "c-red"))),
                    argumentSet("제외 룰에 걸리는 조합 오버라이드",
                            ok, null, List.of(new ExclusionRuleCommand(List.of("c-blue", "s-s"), null)),
                            List.of(override("c-blue", "s-s"))),
                    argumentSet("부분조건 제외 룰(파랑 전체)에 포함되는 조합 오버라이드",
                            ok, null, List.of(new ExclusionRuleCommand(List.of("c-blue"), null)),
                            List.of(override("c-blue", "s-m"))),
                    argumentSet("오버라이드 판매가 음수",
                            ok, null, null,
                            List.of(new CombinationOverrideCommand(List.of("c-red", "s-s"), -1, null, null, null))),
                    argumentSet("오버라이드 정가 음수",
                            ok, null, null,
                            List.of(new CombinationOverrideCommand(List.of("c-red", "s-s"), null, -1, null, null))),
                    argumentSet("오버라이드 재고 음수",
                            ok, null, null,
                            List.of(new CombinationOverrideCommand(List.of("c-red", "s-s"), null, null, -1, null))),
                    argumentSet("오버라이드 SKU 공백",
                            ok, null, null,
                            List.of(new CombinationOverrideCommand(List.of("c-red", "s-s"), null, null, null, "  "))),
                    argumentSet("오버라이드 SKU 중복",
                            ok, null, null, List.of(
                                    new CombinationOverrideCommand(List.of("c-red", "s-s"), null, null, null, "SKU-1"),
                                    new CombinationOverrideCommand(List.of("c-red", "s-m"), null, null, null, "SKU-1"))));
        }

        @ParameterizedTest
        @MethodSource("invalidInputs")
        @DisplayName("규칙 위반 입력은 InvalidProductOptionException으로 거절한다")
        void rejects(
                List<ProductOptionTypeCommand> types,
                List<SurchargeRuleCommand> surcharges,
                List<ExclusionRuleCommand> exclusions,
                List<CombinationOverrideCommand> overrides) {
            assertThatThrownBy(() -> ProductOptionRuleAssembler.validate(types, surcharges, exclusions, overrides))
                    .isInstanceOfSatisfying(InvalidProductOptionException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(ProductErrorCode.INVALID_PRODUCT_OPTION));
        }
    }

    @Nested
    @DisplayName("resolveRefs")
    class ResolveRefs {

        @Test
        @DisplayName("저장본의 타입·값 순서와 무관하게 (타입명, 옵션값) 자연키로 ref를 옵션값 id에 매칭한다")
        void matchesByNaturalKeyRegardlessOfOrder() {
            Map<String, UUID> refToId = ProductOptionRuleAssembler.resolveRefs(colorAndSize(), savedColorAndSizeReversed());

            assertThat(refToId).containsExactlyInAnyOrderEntriesOf(REF_TO_ID);
        }

        @Test
        @DisplayName("ref가 없는 옵션값은 결과에 넣지 않는다")
        void skipsValuesWithoutRef() {
            List<ProductOptionTypeCommand> types = List.of(
                    type("색상", value("빨강", "c-red"), value("파랑", null)),
                    type("사이즈", value("S", null), value("M", null)));

            Map<String, UUID> refToId = ProductOptionRuleAssembler.resolveRefs(types, savedColorAndSizeReversed());

            assertThat(refToId).containsExactly(Map.entry("c-red", RED));
        }
    }

    @Nested
    @DisplayName("룰 변환")
    class RuleConversion {

        @Test
        @DisplayName("추가금 룰은 ref를 옵션값 id로 바꾸고 금액을 보존한다 (ref 순서 유지)")
        void convertsSurchargeRules() {
            int amount = Arbitraries.integers().between(0, 1_000_000).sample();

            List<SurchargeRule> rules = ProductOptionRuleAssembler.toSurchargeRules(
                    List.of(new SurchargeRuleCommand(List.of("s-m", "c-red"), amount)), REF_TO_ID);

            assertThat(rules).containsExactly(new SurchargeRule(List.of(M, RED), amount));
        }

        @Test
        @DisplayName("제외 룰은 ref를 옵션값 id로 바꾸고 사유(null 포함)를 보존한다")
        void convertsExclusionRules() {
            List<ExclusionRule> rules = ProductOptionRuleAssembler.toExclusionRules(List.of(
                    new ExclusionRuleCommand(List.of("c-blue", "s-s"), "미생산"),
                    new ExclusionRuleCommand(List.of("c-red"), null)), REF_TO_ID);

            assertThat(rules).containsExactly(
                    new ExclusionRule(List.of(BLUE, S), "미생산"),
                    new ExclusionRule(List.of(RED), null));
        }

        @Test
        @DisplayName("룰 커맨드가 null이면 빈 목록")
        void nullCommandsBecomeEmpty() {
            assertThat(ProductOptionRuleAssembler.toSurchargeRules(null, REF_TO_ID)).isEmpty();
            assertThat(ProductOptionRuleAssembler.toExclusionRules(null, REF_TO_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("applyOverrides")
    class ApplyOverrides {

        @Test
        @DisplayName("지정한 판매가·정가·재고(예약 0)·SKU를 대상 조합에만 덮어쓰고 키·옵션값·판매여부는 보존한다")
        void overwritesTargetFields() {
            int price = Arbitraries.integers().between(0, 1_000_000).sample();
            int originalPrice = Arbitraries.integers().between(0, 1_000_000).sample();
            int stock = Arbitraries.integers().between(0, 10_000).sample();
            List<ProductOptionCombination> generated = generatedCombinations();

            List<ProductOptionCombination> result = ProductOptionRuleAssembler.applyOverrides(
                    generated,
                    List.of(new CombinationOverrideCommand(List.of("s-m", "c-red"), price, originalPrice, stock, "TS-RED-M")),
                    REF_TO_ID);

            ProductOptionCombination before = combinationOf(generated, RED, M);
            assertThat(combinationOf(result, RED, M)).satisfies(c -> {
                assertThat(c.price()).isEqualTo(price);
                assertThat(c.originalPrice()).isEqualTo(originalPrice);
                assertThat(c.stock().stock()).isEqualTo(stock);
                assertThat(c.stock().reservedStock()).isZero();
                assertThat(c.sku().value()).isEqualTo("TS-RED-M");
                assertThat(c.combinationKey()).isEqualTo(before.combinationKey());
                assertThat(c.optionValueIds()).isEqualTo(before.optionValueIds());
                assertThat(c.productId()).isEqualTo(PRODUCT_ID);
                assertThat(c.isAvailable()).isTrue();
                assertThat(c.createdAt()).isEqualTo(NOW);
            });
        }

        @Test
        @DisplayName("null 필드는 판매가·재고를 생성값으로 두고, 정가·SKU는 미설정(null)으로 둔다")
        void nullFieldsKeepGeneratedValues() {
            List<ProductOptionCombination> generated = generatedCombinations();

            List<ProductOptionCombination> result = ProductOptionRuleAssembler.applyOverrides(
                    generated, List.of(override("c-blue", "s-s")), REF_TO_ID);

            assertThat(combinationOf(result, BLUE, S)).isEqualTo(combinationOf(generated, BLUE, S));
        }

        @Test
        @DisplayName("오버라이드 대상이 아닌 조합은 그대로 두고, 전체 개수·순서를 유지한다")
        void leavesOthersUntouchedAndKeepsOrder() {
            List<ProductOptionCombination> generated = generatedCombinations();

            List<ProductOptionCombination> result = ProductOptionRuleAssembler.applyOverrides(
                    generated,
                    List.of(new CombinationOverrideCommand(List.of("c-red", "s-s"), 1, null, null, null)),
                    REF_TO_ID);

            assertThat(result).hasSameSizeAs(generated);
            assertThat(result).extracting(ProductOptionCombination::combinationKey)
                    .containsExactlyElementsOf(generated.stream().map(ProductOptionCombination::combinationKey).toList());
            assertThat(result).filteredOn(c -> !Set.copyOf(c.optionValueIds()).equals(Set.of(RED, S)))
                    .containsExactlyElementsOf(generated.stream()
                            .filter(c -> !Set.copyOf(c.optionValueIds()).equals(Set.of(RED, S)))
                            .toList());
        }

        @Test
        @DisplayName("오버라이드가 null이면 생성 결과를 그대로 돌려준다")
        void nullOverridesReturnGenerated() {
            List<ProductOptionCombination> generated = generatedCombinations();

            assertThat(ProductOptionRuleAssembler.applyOverrides(generated, null, REF_TO_ID))
                    .containsExactlyElementsOf(generated);
        }
    }

    @Nested
    @DisplayName("toConfig")
    class ToConfig {

        @Test
        @DisplayName("룰이 하나도 없으면 null (config 행을 만들지 않는다)")
        void noRulesIsNull() {
            assertThat(ProductOptionRuleAssembler.toConfig(List.of(), List.of())).isNull();
        }

        @Test
        @DisplayName("DDL jsonb 형식으로 직렬화한다 — [{valueIds, amount}] / [{valueIds, reason}]")
        void serializesInDdlShape() {
            ProductOptionConfigModel config = ProductOptionRuleAssembler.toConfig(
                    List.of(new SurchargeRule(List.of(RED, M), 500)),
                    List.of(new ExclusionRule(List.of(BLUE, S), "미생산")));

            JsonNode surcharge = JSON.readTree(config.surchargeRules());
            assertThat(surcharge.isArray()).isTrue();
            assertThat(surcharge).hasSize(1);
            assertThat(surcharge.get(0).propertyNames()).containsExactlyInAnyOrder("valueIds", "amount");
            assertThat(surcharge.get(0).get("valueIds").valueStream().map(JsonNode::asString))
                    .containsExactly(RED.toString(), M.toString());
            assertThat(surcharge.get(0).get("amount").asInt()).isEqualTo(500);

            JsonNode exclusion = JSON.readTree(config.exclusionRules());
            assertThat(exclusion).hasSize(1);
            assertThat(exclusion.get(0).propertyNames()).containsExactlyInAnyOrder("valueIds", "reason");
            assertThat(exclusion.get(0).get("valueIds").valueStream().map(JsonNode::asString))
                    .containsExactly(BLUE.toString(), S.toString());
            assertThat(exclusion.get(0).get("reason").asString()).isEqualTo("미생산");
        }

        @Test
        @DisplayName("한쪽 룰만 있으면 다른 쪽 컬럼은 null")
        void emptySideIsNull() {
            ProductOptionConfigModel onlySurcharge = ProductOptionRuleAssembler.toConfig(
                    List.of(new SurchargeRule(List.of(RED), 100)), List.of());
            ProductOptionConfigModel onlyExclusion = ProductOptionRuleAssembler.toConfig(
                    List.of(), List.of(new ExclusionRule(List.of(RED), null)));

            assertThat(onlySurcharge.surchargeRules()).isNotNull();
            assertThat(onlySurcharge.exclusionRules()).isNull();
            assertThat(onlyExclusion.surchargeRules()).isNull();
            assertThat(onlyExclusion.exclusionRules()).isNotNull();
        }
    }
}
