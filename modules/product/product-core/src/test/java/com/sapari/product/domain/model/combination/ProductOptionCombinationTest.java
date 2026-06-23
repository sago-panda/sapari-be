package com.sapari.product.domain.model.combination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navercorp.fixturemonkey.ArbitraryBuilder;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProductOptionCombination 변경 메서드")
class ProductOptionCombinationTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    /**
     * 변경 검증에 쓸 기준 조합. 불변식 제약 필드(가격·재고·키·소유자)만 핀하고 나머지는 FixtureMonkey가 채운다.
     */
    private static ArbitraryBuilder<ProductOptionCombination> aCombination() {
        return FM.giveMeBuilder(ProductOptionCombination.class)
                .setNull("id")
                .set("productId", PRODUCT_ID)
                .set("combinationKey", CombinationKey.of("1:2"))
                // Sku는 빈 문자열을 거부하는 VO다. FixtureMonkey가 중첩 VO를 eager 생성하며
                // 가끔 빈 값으로 만들어 예외를 내므로, 유효 인스턴스로 핀한다(값 자체는 검증 대상 아님).
                .set("sku", Sku.of("SKU-TEST"))
                .set("price", 10_000)
                .set("originalPrice", 12_000)
                .set("stock", Stock.of(100, 10))
                .set("isAvailable", true)
                .set("optionValueIds", List.of(UUID.randomUUID(), UUID.randomUUID()))
                .setNull("searchIndexedAt")
                .set("createdAt", NOW)
                .set("updatedAt", NOW);
    }

    @Nested
    @DisplayName("changePrice (즉시 가격 변경)")
    class ChangePrice {

        @Test
        @DisplayName("판매가·정가를 갱신하고 updatedAt을 바꾸되 재고·키·생성시각은 보존한다")
        void changesPrice() {
            ProductOptionCombination origin = aCombination().sample();

            ProductOptionCombination changed = origin.changePrice(8_900, 12_000, LATER);

            assertThat(changed.price()).isEqualTo(8_900);
            assertThat(changed.originalPrice()).isEqualTo(12_000);
            assertThat(changed.updatedAt()).isEqualTo(LATER);
            assertThat(changed.createdAt()).isEqualTo(NOW);
            assertThat(changed.stock()).isEqualTo(origin.stock());
            assertThat(changed.combinationKey()).isEqualTo(origin.combinationKey());
        }

        @Test
        @DisplayName("originalPrice를 null로 변경하면 정가(취소선)가 사라진다")
        void clearsOriginalPrice() {
            ProductOptionCombination origin = aCombination().sample();

            ProductOptionCombination changed = origin.changePrice(8_900, null, LATER);

            assertThat(changed.originalPrice()).isNull();
            assertThat(changed.price()).isEqualTo(8_900);
        }

        @Test
        @DisplayName("음수 가격이면 IllegalArgumentException (불변식)")
        void rejectsNegativePrice() {
            ProductOptionCombination origin = aCombination().sample();

            assertThatThrownBy(() -> origin.changePrice(-1, null, LATER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("changeStock (즉시 재고 변경)")
    class ChangeStock {

        @Test
        @DisplayName("총 재고를 갱신하고 예약 재고는 유지하며 가용 재고를 다시 계산한다")
        void changesStock() {
            ProductOptionCombination origin = aCombination().sample(); // stock=100, reserved=10

            ProductOptionCombination changed = origin.changeStock(50, LATER);

            assertThat(changed.stock().stock()).isEqualTo(50);
            assertThat(changed.stock().reservedStock()).isEqualTo(10);
            assertThat(changed.availableStock()).isEqualTo(40);
            assertThat(changed.updatedAt()).isEqualTo(LATER);
        }

        @Test
        @DisplayName("재고를 늘리면 예약 재고는 유지되고 가용 재고가 함께 늘어난다")
        void increasesStock() {
            ProductOptionCombination origin = aCombination().sample(); // stock=100, reserved=10

            ProductOptionCombination changed = origin.changeStock(200, LATER);

            assertThat(changed.stock().stock()).isEqualTo(200);
            assertThat(changed.stock().reservedStock()).isEqualTo(10);
            assertThat(changed.availableStock()).isEqualTo(190);
        }

        @Test
        @DisplayName("재고만 바꾸고 가격·키·판매여부는 보존한다")
        void preservesOtherFields() {
            ProductOptionCombination origin = aCombination().sample();

            ProductOptionCombination changed = origin.changeStock(50, LATER);

            assertThat(changed.price()).isEqualTo(origin.price());
            assertThat(changed.combinationKey()).isEqualTo(origin.combinationKey());
            assertThat(changed.isAvailable()).isEqualTo(origin.isAvailable());
        }

        @Test
        @DisplayName("새 재고가 예약 재고보다 작으면 IllegalArgumentException (0<=reserved<=stock)")
        void rejectsStockBelowReserved() {
            ProductOptionCombination origin = aCombination().sample(); // reserved=10

            assertThatThrownBy(() -> origin.changeStock(5, LATER))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("판매 가능 여부 토글")
    class Availability {

        @Test
        @DisplayName("discontinue는 isAvailable을 false로 만들고 updatedAt을 갱신한다 (단종)")
        void discontinues() {
            ProductOptionCombination origin = aCombination().set("isAvailable", true).sample();

            ProductOptionCombination changed = origin.discontinue(LATER);

            assertThat(changed.isAvailable()).isFalse();
            assertThat(changed.updatedAt()).isEqualTo(LATER);
            // 단종은 판매여부만 바꾼다 — 가격·재고는 보존
            assertThat(changed.price()).isEqualTo(origin.price());
            assertThat(changed.stock()).isEqualTo(origin.stock());
        }

        @Test
        @DisplayName("makeAvailable은 단종 조합을 다시 판매 가능으로 만든다")
        void makesAvailable() {
            ProductOptionCombination discontinued = aCombination().set("isAvailable", false).sample();

            ProductOptionCombination changed = discontinued.makeAvailable(LATER);

            assertThat(changed.isAvailable()).isTrue();
            assertThat(changed.updatedAt()).isEqualTo(LATER);
        }
    }
}
