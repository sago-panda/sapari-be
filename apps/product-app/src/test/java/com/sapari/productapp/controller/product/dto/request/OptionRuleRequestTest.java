package com.sapari.productapp.controller.product.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.command.ExclusionRuleCommand;
import com.sapari.product.command.SurchargeRuleCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;

/**
 * 옵션 룰·조합 오버라이드 요청 DTO 테스트. 스칼라 제약(Bean Validation → 400)과 커맨드 변환(ref·룰·오버라이드 전달)을 고정한다.
 * 요청 전체를 봐야 하는 구조 규칙(ref 존재·완전 조합 등)은 서비스 테스트가 담당한다.
 */
@DisplayName("옵션 룰·조합 오버라이드 요청 DTO")
class OptionRuleRequestTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    /**
     * 위반 필드 경로 집합.
     */
    private static Set<String> violatedPaths(Object request) {
        return validator.validate(request)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static OptionTypeRequest colorWithRefs() {
        return new OptionTypeRequest("색상", null, (short) 1, List.of(
                new OptionValueRequest("빨강", null, null, 0, (short) 1, "c-red"),
                new OptionValueRequest("파랑", null, null, 0, (short) 2, "c-blue")));
    }

    private static CreateProductRequest createRequest(
            List<SurchargeRuleRequest> surcharges,
            List<ExclusionRuleRequest> exclusions,
            List<CombinationOverrideRequest> combinations) {
        return new CreateProductRequest(
                1001L, "베이직 티셔츠", null, 19_900, null, null, List.of(), List.of(colorWithRefs()), 100,
                surcharges, exclusions, combinations);
    }

    @Nested
    @DisplayName("Bean Validation")
    class Constraints {

        @Test
        @DisplayName("룰·오버라이드를 생략(null)한 기존 요청은 유효하다 (하위 호환)")
        void omittedRulesAreValid() {
            assertThat(violatedPaths(createRequest(null, null, null))).isEmpty();
        }

        @Test
        @DisplayName("정상 룰·오버라이드와 경계값(금액·가격·재고 0, SKU 100자, 사유 200자)은 유효하다")
        void wellFormedRulesAreValid() {
            CreateProductRequest request = createRequest(
                    List.of(new SurchargeRuleRequest(List.of("c-red"), 0)),
                    List.of(new ExclusionRuleRequest(List.of("c-blue"), "가".repeat(200))),
                    List.of(new CombinationOverrideRequest(List.of("c-red"), 0, 0, 0, "S".repeat(100))));

            assertThat(violatedPaths(request)).isEmpty();
        }

        @Test
        @DisplayName("valueRefs가 비었거나 원소가 공백이면 400 대상이다 (중첩 @Valid 전파)")
        void emptyOrBlankRefsAreInvalid() {
            CreateProductRequest request = createRequest(
                    List.of(new SurchargeRuleRequest(List.of(), 100)),
                    List.of(new ExclusionRuleRequest(List.of(" "), null)),
                    List.of(new CombinationOverrideRequest(null, null, null, null, null)));

            assertThat(violatedPaths(request)).containsExactlyInAnyOrder(
                    "surchargeRules[0].valueRefs",
                    "exclusionRules[0].valueRefs[0].<list element>",
                    "combinations[0].valueRefs");
        }

        @Test
        @DisplayName("목록에 null 원소가 있으면 400 대상이다 (toCommand NPE → 500 방지, 옵션 타입·값 목록 포함)")
        void nullElementsAreInvalid() {
            List<OptionValueRequest> valuesWithNull = new ArrayList<>();
            valuesWithNull.add(null);
            OptionTypeRequest typeWithNullValue = new OptionTypeRequest("사이즈", null, (short) 2, valuesWithNull);
            List<OptionTypeRequest> types = new ArrayList<>(List.of(typeWithNullValue));
            types.add(null);
            CreateProductRequest request = new CreateProductRequest(
                    1001L, "베이직 티셔츠", null, 19_900, null, null, List.of(), types, 100,
                    Collections.singletonList(null), Collections.singletonList(null), Collections.singletonList(null));

            assertThat(violatedPaths(request)).containsExactlyInAnyOrder(
                    "optionTypes[0].values[0].<list element>",
                    "optionTypes[1].<list element>",
                    "surchargeRules[0].<list element>",
                    "exclusionRules[0].<list element>",
                    "combinations[0].<list element>");
        }

        @Test
        @DisplayName("조합 가격·재고 수정 목록의 null 원소도 400 대상이다")
        void nullCombinationUpdateElementIsInvalid() {
            UpdateCombinationPriceStockRequest request =
                    new UpdateCombinationPriceStockRequest(Collections.singletonList(null));

            assertThat(violatedPaths(request)).containsExactly("combinations[0].<list element>");
        }

        @Test
        @DisplayName("음수 금액·판매가·정가·재고와 길이 초과 SKU·사유·ref는 400 대상이다")
        void negativeAndOversizedFieldsAreInvalid() {
            OptionTypeRequest longRef = new OptionTypeRequest("사이즈", null, (short) 2, List.of(
                    new OptionValueRequest("S", null, null, 0, (short) 1, "r".repeat(101))));
            CreateProductRequest request = new CreateProductRequest(
                    1001L, "베이직 티셔츠", null, 19_900, null, null, List.of(), List.of(longRef), 100,
                    List.of(new SurchargeRuleRequest(List.of("c-red"), -1)),
                    List.of(new ExclusionRuleRequest(List.of("c-red"), "가".repeat(201))),
                    List.of(new CombinationOverrideRequest(List.of("c-red"), -1, -1, -1, "S".repeat(101))));

            assertThat(violatedPaths(request)).containsExactlyInAnyOrder(
                    "optionTypes[0].values[0].ref",
                    "surchargeRules[0].amount",
                    "exclusionRules[0].reason",
                    "combinations[0].price",
                    "combinations[0].originalPrice",
                    "combinations[0].stock",
                    "combinations[0].sku");
        }

        @Test
        @DisplayName("룰·오버라이드 목록이 500개를 넘으면 400 대상이다 (옵션 수정 요청도 동일)")
        void tooManyEntriesAreInvalid() {
            List<SurchargeRuleRequest> surcharges =
                    Collections.nCopies(501, new SurchargeRuleRequest(List.of("c-red"), 1));
            List<ExclusionRuleRequest> exclusions =
                    Collections.nCopies(501, new ExclusionRuleRequest(List.of("c-red"), null));
            List<CombinationOverrideRequest> combinations =
                    Collections.nCopies(501, new CombinationOverrideRequest(List.of("c-red"), 1, null, null, null));
            UpdateProductOptionsRequest request = new UpdateProductOptionsRequest(
                    List.of(colorWithRefs()), 100, 3L, surcharges, exclusions, combinations);

            assertThat(violatedPaths(request))
                    .containsExactlyInAnyOrder("surchargeRules", "exclusionRules", "combinations");
        }
    }

    @Nested
    @DisplayName("커맨드 변환")
    class ToCommand {

        @Test
        @DisplayName("등록: 옵션값 ref·추가금·제외·오버라이드를 커맨드에 그대로 옮긴다")
        void createCarriesRefsAndRules() {
            CreateProductCommand command = createRequest(
                    List.of(new SurchargeRuleRequest(List.of("c-red"), 500)),
                    List.of(new ExclusionRuleRequest(List.of("c-blue"), "미생산")),
                    List.of(new CombinationOverrideRequest(List.of("c-red"), 18_900, 22_000, 30, "TS-RED")))
                    .toCommand(SELLER_ID);

            assertThat(command.optionTypes().get(0).values())
                    .extracting(v -> v.ref())
                    .containsExactly("c-red", "c-blue");
            assertThat(command.surchargeRules()).containsExactly(new SurchargeRuleCommand(List.of("c-red"), 500));
            assertThat(command.exclusionRules()).containsExactly(new ExclusionRuleCommand(List.of("c-blue"), "미생산"));
            assertThat(command.combinations())
                    .containsExactly(new CombinationOverrideCommand(List.of("c-red"), 18_900, 22_000, 30, "TS-RED"));
        }

        @Test
        @DisplayName("등록: 룰·오버라이드를 생략하면 빈 목록으로 옮긴다")
        void createOmittedRulesBecomeEmpty() {
            CreateProductCommand command = createRequest(null, null, null).toCommand(SELLER_ID);

            assertThat(command.surchargeRules()).isEmpty();
            assertThat(command.exclusionRules()).isEmpty();
            assertThat(command.combinations()).isEmpty();
        }

        @Test
        @DisplayName("옵션 수정: 룰·오버라이드를 커맨드에 옮기고, 생략하면 빈 목록(기존 룰 삭제 의미)으로 옮긴다")
        void updateCarriesRules() {
            UpdateProductOptionsCommand withRules = new UpdateProductOptionsRequest(
                    List.of(colorWithRefs()), 100, 3L,
                    List.of(new SurchargeRuleRequest(List.of("c-red"), 500)),
                    List.of(new ExclusionRuleRequest(List.of("c-blue"), null)),
                    List.of(new CombinationOverrideRequest(List.of("c-red"), null, null, 5, null)))
                    .toCommand(SELLER_ID, PRODUCT_ID);
            UpdateProductOptionsCommand withoutRules = new UpdateProductOptionsRequest(
                    List.of(colorWithRefs()), 100, 3L, null, null, null)
                    .toCommand(SELLER_ID, PRODUCT_ID);

            assertThat(withRules.surchargeRules()).containsExactly(new SurchargeRuleCommand(List.of("c-red"), 500));
            assertThat(withRules.exclusionRules()).containsExactly(new ExclusionRuleCommand(List.of("c-blue"), null));
            assertThat(withRules.combinations())
                    .containsExactly(new CombinationOverrideCommand(List.of("c-red"), null, null, 5, null));
            assertThat(withRules.expectedVersion()).isEqualTo(3L);
            assertThat(withoutRules.surchargeRules()).isEmpty();
            assertThat(withoutRules.exclusionRules()).isEmpty();
            assertThat(withoutRules.combinations()).isEmpty();
        }
    }
}
