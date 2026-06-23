package com.sapari.product.domain.model.combination;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.TooManyCombinationsException;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CombinationGenerator 도메인 서비스")
class CombinationGeneratorTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final int BASE_PRICE = 10_000;

    // 정렬·키 검증을 결정적으로 하기 위해 옵션값 id를 고정한다.
    private static final UUID RED = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLUE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID S = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID M = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID COTTON = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID WOOL = UUID.fromString("00000000-0000-0000-0000-000000000022");

    private static ProductOptionValueModel value(UUID id, String name, int priceDelta, short sortOrder) {
        return new ProductOptionValueModel(id, null, name, null, priceDelta, sortOrder);
    }

    private static ProductOptionTypeModel type(String name, short sortOrder, ProductOptionValueModel... values) {
        return new ProductOptionTypeModel(UUID.randomUUID(), null, name, sortOrder, List.of(values));
    }

    private static String key(UUID... ids) {
        return Stream.of(ids)
                .sorted()
                .map(UUID::toString)
                .collect(joining(":"));
    }

    @Nested
    @DisplayName("데카르트 곱")
    class Cartesian {

        @Test
        @DisplayName("타입 2개(2값 × 2값)면 조합 4개를 만들고, 각 조합 키는 옵션값 id 오름차순 join이다")
        void generatesCartesianProduct() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 0, (short) 1), value(BLUE, "파랑", 0, (short) 2));
            ProductOptionTypeModel size = type("사이즈", (short) 2,
                    value(S, "S", 0, (short) 1), value(M, "M", 0, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size), List.of(), List.of(), 0, NOW);

            assertThat(result).hasSize(4);
            assertThat(result)
                    .extracting(c -> c.combinationKey().value())
                    .containsExactlyInAnyOrder(
                            key(RED, S), key(RED, M), key(BLUE, S), key(BLUE, M));
        }

        @Test
        @DisplayName("생성된 조합은 신규 상태로 초기화된다 (id·sku·originalPrice 없음, 시각=now, 판매가능, 재고=defaultStock)")
        void initializesNewCombinationFields() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 0, (short) 1), value(BLUE, "파랑", 0, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color), List.of(), List.of(), 50, NOW);

            assertThat(result).hasSize(2);
            assertThat(result).allSatisfy(c -> {
                assertThat(c.id()).isNull();
                assertThat(c.productId()).isEqualTo(PRODUCT_ID);
                assertThat(c.sku()).isNull();
                assertThat(c.originalPrice()).isNull();
                assertThat(c.isAvailable()).isTrue();
                assertThat(c.price()).isEqualTo(BASE_PRICE);
                assertThat(c.stock().stock()).isEqualTo(50);
                assertThat(c.stock().reservedStock()).isZero();
                assertThat(c.createdAt()).isEqualTo(NOW);
                assertThat(c.updatedAt()).isEqualTo(NOW);
                assertThat(c.optionValueIds()).hasSize(1);
            });
            assertThat(result)
                    .flatExtracting(ProductOptionCombination::optionValueIds)
                    .containsExactlyInAnyOrder(RED, BLUE);
        }

        @Test
        @DisplayName("타입 3개(2×2×2)면 조합 8개를 만들고 각 조합은 옵션값 3개를 가진다")
        void generatesThreeDimensionalProduct() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 0, (short) 1), value(BLUE, "파랑", 0, (short) 2));
            ProductOptionTypeModel size = type("사이즈", (short) 2,
                    value(S, "S", 0, (short) 1), value(M, "M", 0, (short) 2));
            ProductOptionTypeModel material = type("소재", (short) 3,
                    value(COTTON, "면", 0, (short) 1), value(WOOL, "울", 0, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size, material), List.of(), List.of(), 0, NOW);

            assertThat(result).hasSize(8);
            assertThat(result).allSatisfy(c -> assertThat(c.optionValueIds()).hasSize(3));
            assertThat(result).extracting(c -> c.combinationKey().value()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("옵션 타입이 없으면 빈 조합 목록을 반환한다 (옵션 없는 단일 상품)")
        void noOptionTypesYieldsEmpty() {
            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(), List.of(), List.of(), 0, NOW);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("가격 계산")
    class Pricing {

        @Test
        @DisplayName("price = basePrice + Σ(옵션값 priceDelta)")
        void sumsPriceDeltas() {
            ProductOptionTypeModel size = type("사이즈", (short) 1,
                    value(S, "S", 0, (short) 1), value(M, "M", 1_000, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(size), List.of(), List.of(), 0, NOW);

            assertThat(result)
                    .filteredOn(c -> c.optionValueIds().contains(M))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(11_000));
            assertThat(result)
                    .filteredOn(c -> c.optionValueIds().contains(S))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(10_000));
        }

        @Test
        @DisplayName("여러 타입의 priceDelta가 한 조합에서 모두 합산된다")
        void sumsPriceDeltasAcrossTypes() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 500, (short) 1), value(BLUE, "파랑", 0, (short) 2));
            ProductOptionTypeModel size = type("사이즈", (short) 2,
                    value(S, "S", 0, (short) 1), value(M, "M", 1_000, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size), List.of(), List.of(), 0, NOW);

            // 빨강(+500) + M(+1000) → 11500
            assertThat(result)
                    .filteredOn(c -> c.combinationKey().value().equals(key(RED, M)))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(11_500));
        }

        @Test
        @DisplayName("surchargeRule의 valueIds를 모두 포함하는 조합에만 amount를 가산한다")
        void appliesSurchargeOnlyToMatchingCombos() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 0, (short) 1), value(BLUE, "파랑", 0, (short) 2));
            ProductOptionTypeModel size = type("사이즈", (short) 2,
                    value(S, "S", 0, (short) 1), value(M, "M", 0, (short) 2));

            // 빨강 + M 조합에만 500원 가산
            SurchargeRule rule = new SurchargeRule(List.of(RED, M), 500);

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size), List.of(rule), List.of(), 0, NOW);

            assertThat(result)
                    .filteredOn(c -> c.combinationKey().value().equals(key(RED, M)))
                    .singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(10_500));
            // 나머지 3개 조합은 가산 없음
            assertThat(result)
                    .filteredOn(c -> !c.combinationKey().value().equals(key(RED, M)))
                    .allSatisfy(c -> assertThat(c.price()).isEqualTo(10_000));
        }

        @Test
        @DisplayName("한 조합에 매칭되는 surchargeRule이 여러 개면 모두 합산된다")
        void sumsMultipleMatchingSurcharges() {
            ProductOptionTypeModel color = type("색상", (short) 1, value(RED, "빨강", 0, (short) 1));
            ProductOptionTypeModel size = type("사이즈", (short) 2, value(M, "M", 0, (short) 1));

            List<SurchargeRule> rules = List.of(
                    new SurchargeRule(List.of(RED), 300),       // 빨강 포함 → +300
                    new SurchargeRule(List.of(RED, M), 700));   // 빨강+M 포함 → +700

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size), rules, List.of(), 0, NOW);

            assertThat(result).singleElement()
                    .satisfies(c -> assertThat(c.price()).isEqualTo(11_000)); // 10000 + 300 + 700
        }
    }

    @Nested
    @DisplayName("제외 룰")
    class Exclusion {

        @Test
        @DisplayName("exclusionRule의 valueIds를 모두 포함하는 조합은 생성하지 않는다")
        void omitsExcludedCombos() {
            ProductOptionTypeModel color = type("색상", (short) 1,
                    value(RED, "빨강", 0, (short) 1), value(BLUE, "파랑", 0, (short) 2));
            ProductOptionTypeModel size = type("사이즈", (short) 2,
                    value(S, "S", 0, (short) 1), value(M, "M", 0, (short) 2));

            ExclusionRule rule = new ExclusionRule(List.of(BLUE, S));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(color, size), List.of(), List.of(rule), 0, NOW);

            assertThat(result).hasSize(3);
            assertThat(result)
                    .extracting(c -> c.combinationKey().value())
                    .doesNotContain(key(BLUE, S))
                    .containsExactlyInAnyOrder(key(RED, S), key(RED, M), key(BLUE, M));
        }
    }

    @Nested
    @DisplayName("재고 기본값")
    class DefaultStock {

        @Test
        @DisplayName("생성된 조합은 defaultStock으로 시작하고 예약 재고는 0이다")
        void appliesDefaultStock() {
            ProductOptionTypeModel size = type("사이즈", (short) 1,
                    value(S, "S", 0, (short) 1), value(M, "M", 0, (short) 2));

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(size), List.of(), List.of(), 100, NOW);

            assertThat(result).allSatisfy(c -> {
                assertThat(c.stock().stock()).isEqualTo(100);
                assertThat(c.stock().reservedStock()).isZero();
                assertThat(c.availableStock()).isEqualTo(100);
            });
        }
    }

    @Nested
    @DisplayName("검증 실패")
    class Validation {

        @Test
        @DisplayName("값이 하나도 없는 옵션 타입이 있으면 InvalidProductOptionException")
        void rejectsTypeWithoutValues() {
            ProductOptionTypeModel empty =
                    new ProductOptionTypeModel(UUID.randomUUID(), null, "색상", (short) 1, List.of());

            assertThatThrownBy(() -> CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(empty), List.of(), List.of(), 0, NOW))
                    .isInstanceOf(InvalidProductOptionException.class);
        }

        @Test
        @DisplayName("조합 수가 500을 초과하면 TooManyCombinationsException (8×8×8 = 512)")
        void rejectsTooManyCombinations() {
            ProductOptionTypeModel t1 = bigType("t1", (short) 1, 8);
            ProductOptionTypeModel t2 = bigType("t2", (short) 2, 8);
            ProductOptionTypeModel t3 = bigType("t3", (short) 3, 8);

            assertThatThrownBy(() -> CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(t1, t2, t3), List.of(), List.of(), 0, NOW))
                    .isInstanceOf(TooManyCombinationsException.class);
        }

        @Test
        @DisplayName("조합 수가 정확히 500이면 통과한다 (경계: 10 × 10 × 5)")
        void allowsExactlyMaxCombinations() {
            ProductOptionTypeModel t1 = bigType("t1", (short) 1, 10);
            ProductOptionTypeModel t2 = bigType("t2", (short) 2, 10);
            ProductOptionTypeModel t3 = bigType("t3", (short) 3, 5);

            List<ProductOptionCombination> result = CombinationGenerator.generate(
                    PRODUCT_ID, BASE_PRICE, List.of(t1, t2, t3), List.of(), List.of(), 0, NOW);

            assertThat(result).hasSize(CombinationGenerator.MAX_COMBINATIONS);
        }

        private ProductOptionTypeModel bigType(String name, short sortOrder, int valueCount) {
            List<ProductOptionValueModel> values = IntStream.range(0, valueCount)
                    .mapToObj(i -> value(UUID.randomUUID(), name + "-v" + i, 0, (short) (i + 1)))
                    .toList();
            return new ProductOptionTypeModel(UUID.randomUUID(), null, name, sortOrder, values);
        }
    }
}
