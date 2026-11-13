package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationOverrideCommand;
import com.sapari.product.command.ExclusionRuleCommand;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.command.SurchargeRuleCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;
import com.sapari.product.domain.exception.InvalidProductOptionException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ExclusionRule;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionConfigModel;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateProductOptionsService")
class UpdateProductOptionsServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Long CATEGORY_ID = 1001L;
    private static final UUID NEW_S = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID NEW_M = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");
    private static final Long EXPECTED_VERSION = 7L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionCombinationRepository combinationRepository;

    // 실제 TimeProvider를 고정 Clock으로 — 수정 시각 now()는 LATER를 결정적으로 반환(스텁 불필요)
    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(LATER, ZoneOffset.UTC));

    private UpdateProductOptionsService service;

    @BeforeEach
    void setUp() {
        service = new UpdateProductOptionsService(productRepository, combinationRepository, timeProvider);
    }

    private static Product onSaleProduct(UUID sellerId) {
        return Product.create(
                        sellerId, CATEGORY_ID, "상품", "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW)
                .toBuilder()
                .id(PRODUCT_ID)
                .build();
    }

    /** productRepository.replaceOptionTypes가 돌려줄, 새 옵션값 id가 부여된 저장본. */
    private static Product savedWithNewOptions() {
        ProductOptionValueModel s = new ProductOptionValueModel(NEW_S, null, "S", null, 0, (short) 1);
        ProductOptionValueModel m = new ProductOptionValueModel(NEW_M, null, "M", null, 0, (short) 2);
        ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 1, List.of(s, m));
        return onSaleProduct(SELLER_ID).toBuilder().optionTypes(List.of(size)).build();
    }

    private static UpdateProductOptionsCommand command() {
        ProductOptionValueCommand s = new ProductOptionValueCommand("S", null, null, 0, (short) 1, null);
        ProductOptionValueCommand m = new ProductOptionValueCommand("M", null, null, 0, (short) 2, null);
        ProductOptionTypeCommand size = new ProductOptionTypeCommand("사이즈", null, (short) 1, List.of(s, m));
        return new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(size), 50, EXPECTED_VERSION, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private List<ProductOptionCombination> captureSavedAll() {
        ArgumentCaptor<List<ProductOptionCombination>> captor = ArgumentCaptor.forClass(List.class);
        then(combinationRepository).should().saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("성공")
    class Success {

        private void stubHappyPath() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(productRepository.replaceOptionTypes(eq(PRODUCT_ID), any())).willReturn(savedWithNewOptions());
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("루트는 클라이언트가 본 version·수정 시각으로 저장하고, 옵션 트리는 기존 그대로 넘긴다(save는 옵션을 쓰지 않음)")
        void savesRootWithExpectedVersion() {
            stubHappyPath();

            service.update(command());

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            then(productRepository).should().save(productCaptor.capture());
            // 클라이언트가 본 version으로 덮어써 stale 비교가 동작하도록 보장한다(§13)
            assertThat(productCaptor.getValue().version()).isEqualTo(EXPECTED_VERSION);
            assertThat(productCaptor.getValue().updatedAt()).isEqualTo(LATER);
            assertThat(productCaptor.getValue().optionTypes()).isEqualTo(onSaleProduct(SELLER_ID).optionTypes());
        }

        @Test
        @DisplayName("새 옵션 타입/값은 replaceOptionTypes로 교체한다 (영속 전이라 옵션값 id는 null)")
        void replacesOptionTypes() {
            stubHappyPath();

            service.update(command());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ProductOptionTypeModel>> typesCaptor = ArgumentCaptor.forClass(List.class);
            then(productRepository).should().replaceOptionTypes(eq(PRODUCT_ID), typesCaptor.capture());
            assertThat(typesCaptor.getValue()).singleElement()
                    .satisfies(type -> {
                        assertThat(type.name()).isEqualTo("사이즈");
                        assertThat(type.values()).extracting(ProductOptionValueModel::value).containsExactly("S", "M");
                        assertThat(type.values()).extracting(ProductOptionValueModel::id).containsOnlyNulls();
                    });
        }

        @Test
        @DisplayName("단종 → 루트 저장(version 검사) → 옵션 교체 → 새 조합 저장 → 캐시 재계산 → config 교체 순으로 진행한다")
        void runsStepsInOrder() {
            stubHappyPath();

            service.update(command());

            InOrder order = inOrder(combinationRepository, productRepository);
            order.verify(combinationRepository).discontinueAllByProductId(PRODUCT_ID, LATER);
            order.verify(productRepository).save(any());
            order.verify(productRepository).replaceOptionTypes(eq(PRODUCT_ID), any());
            order.verify(combinationRepository).saveAll(any());
            order.verify(productRepository).refreshStockPriceCache(PRODUCT_ID, LATER);
            order.verify(productRepository).replaceOptionConfig(PRODUCT_ID, null);
        }

        @Test
        @DisplayName("기존 조합을 벌크로 단종한다 (건당 save 아님, discontinueAllByProductId 1회)")
        void retiresExistingCombinations() {
            stubHappyPath();

            service.update(command());

            then(combinationRepository).should().discontinueAllByProductId(PRODUCT_ID, LATER);
            then(combinationRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("신규 조합은 saveAll로 일괄 저장한다 (건당 save 아님, id=null·판매가능·재고·키·옵션값)")
        void savesNewCombinationsInBatch() {
            stubHappyPath();

            service.update(command());

            List<ProductOptionCombination> newCombos = captureSavedAll();
            assertThat(newCombos).hasSize(2).allSatisfy(c -> {
                assertThat(c.id()).isNull();
                assertThat(c.productId()).isEqualTo(PRODUCT_ID);
                assertThat(c.isAvailable()).isTrue();
                assertThat(c.stock().stock()).isEqualTo(50);
            });
            assertThat(newCombos)
                    .flatExtracting(ProductOptionCombination::optionValueIds)
                    .containsExactlyInAnyOrder(NEW_S, NEW_M);
            assertThat(newCombos)
                    .extracting(c -> c.combinationKey().value())
                    .containsExactlyInAnyOrder(NEW_S.toString(), NEW_M.toString());
        }

        @Test
        @DisplayName("기존 조합 단종 → 새 조합 저장 뒤 상품 캐시를 재계산한다")
        void refreshesCacheAfterRegeneratingCombinations() {
            stubHappyPath();

            service.update(command());

            InOrder inOrder = inOrder(combinationRepository, productRepository);
            inOrder.verify(combinationRepository).discontinueAllByProductId(PRODUCT_ID, LATER);
            inOrder.verify(combinationRepository).saveAll(any());
            inOrder.verify(productRepository).refreshStockPriceCache(PRODUCT_ID, LATER);
        }

        @Test
        @DisplayName("룰이 없어도 config를 null로 교체해 기존 룰(옛 옵션값 id 참조)을 지운다")
        void clearsStaleConfigWithoutRules() {
            stubHappyPath();

            service.update(command());

            then(productRepository).should().replaceOptionConfig(PRODUCT_ID, null);
        }

        @Test
        @DisplayName("SKU 오버라이드가 없으면 기존 조합을 조회하지 않는다 (SKU 재사용 검사 생략)")
        void skipsSkuLookupWithoutSkuOverrides() {
            stubHappyPath();

            service.update(command());

            then(combinationRepository).should(never()).findByProductId(any());
        }

        @Test
        @DisplayName("옵션을 비우면 기존 조합은 벌크 단종하고 기본 조합 1개(재고=defaultStock)를 새로 저장한다 (단일 상품 전환)")
        void emptyOptionsRetiresAllAndCreatesDefaultCombination() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(productRepository.replaceOptionTypes(eq(PRODUCT_ID), any())).willReturn(onSaleProduct(SELLER_ID)); // 옵션 없는 저장본

            service.update(new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(), 50, EXPECTED_VERSION, null, null, null));

            then(combinationRepository).should().discontinueAllByProductId(PRODUCT_ID, LATER);
            assertThat(captureSavedAll()).singleElement().satisfies(c -> {
                assertThat(c.combinationKey().value()).isEqualTo(CombinationKey.DEFAULT);
                assertThat(c.optionValueIds()).isEmpty();
                assertThat(c.price()).isEqualTo(10_000);
                assertThat(c.stock().stock()).isEqualTo(50);
            });
        }
    }

    @Nested
    @DisplayName("옵션 룰·조합 오버라이드")
    class WithOptionRules {

        private static final String REUSED_SKU = "TS-M-OLD";

        /** 사이즈(S s-s · M s-m) 옵션에 룰·오버라이드를 얹은 옵션 수정 커맨드. */
        private static UpdateProductOptionsCommand ruleCommand(
                List<SurchargeRuleCommand> surcharges,
                List<ExclusionRuleCommand> exclusions,
                List<CombinationOverrideCommand> overrides) {
            ProductOptionTypeCommand size = new ProductOptionTypeCommand("사이즈", null, (short) 1, List.of(
                    new ProductOptionValueCommand("S", null, null, 0, (short) 1, "s-s"),
                    new ProductOptionValueCommand("M", null, null, 0, (short) 2, "s-m")));
            return new UpdateProductOptionsCommand(
                    PRODUCT_ID, SELLER_ID, List.of(size), 50, EXPECTED_VERSION, surcharges, exclusions, overrides);
        }

        /** 기존(단종될) 조합 — 주어진 SKU를 점유한다. */
        private static ProductOptionCombination existingCombination(String sku) {
            return ProductOptionCombination.create(
                    PRODUCT_ID, CombinationKey.of("old"), Sku.of(sku), null, 10_000, Stock.of(3, 0), List.of(), NOW);
        }

        private void stubHappyPath() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(productRepository.replaceOptionTypes(eq(PRODUCT_ID), any())).willReturn(savedWithNewOptions());
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("새 옵션값 id로 룰을 해석해 추가금 가산·제외·오버라이드를 새 조합에 반영한다")
        void appliesRulesToRegeneratedCombinations() {
            stubHappyPath();
            given(combinationRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(existingCombination("TS-S-OLD")));

            service.update(ruleCommand(
                    List.of(new SurchargeRuleCommand(List.of("s-m"), 700)),
                    null,
                    List.of(new CombinationOverrideCommand(List.of("s-m"), null, 15_000, 3, "TS-M-NEW"))));

            List<ProductOptionCombination> newCombos = captureSavedAll();
            assertThat(newCombos).hasSize(2);
            assertThat(newCombos).filteredOn(c -> c.optionValueIds().equals(List.of(NEW_S))).singleElement()
                    .satisfies(c -> {
                        assertThat(c.price()).isEqualTo(10_000);
                        assertThat(c.stock().stock()).isEqualTo(50);
                        assertThat(c.sku()).isNull();
                    });
            // M: 판매가는 오버라이드 미지정이라 계산값(기본가 + 추가금), 나머지는 지정값
            assertThat(newCombos).filteredOn(c -> c.optionValueIds().equals(List.of(NEW_M))).singleElement()
                    .satisfies(c -> {
                        assertThat(c.price()).isEqualTo(10_700);
                        assertThat(c.originalPrice()).isEqualTo(15_000);
                        assertThat(c.stock().stock()).isEqualTo(3);
                        assertThat(c.sku().value()).isEqualTo("TS-M-NEW");
                    });
        }

        @Test
        @DisplayName("제외 룰로 조합을 뺀 뒤 config를 새 옵션값 id 기준 룰로 교체한다")
        void replacesConfigWithNewIds() {
            stubHappyPath();

            service.update(ruleCommand(null, List.of(new ExclusionRuleCommand(List.of("s-s"), "단종")), null));

            assertThat(captureSavedAll()).singleElement()
                    .satisfies(c -> assertThat(c.optionValueIds()).containsExactly(NEW_M));

            ArgumentCaptor<ProductOptionConfigModel> captor = ArgumentCaptor.forClass(ProductOptionConfigModel.class);
            then(productRepository).should().replaceOptionConfig(eq(PRODUCT_ID), captor.capture());
            assertThat(captor.getValue().surchargeRules()).isEmpty();
            assertThat(captor.getValue().exclusionRules())
                    .containsExactly(new ExclusionRule(List.of(NEW_S), "단종"));
        }

        @Test
        @DisplayName("기존 조합(단종 포함)이 쓰던 SKU를 재사용하면 InvalidProductOptionException, 단종·저장을 하지 않는다")
        void rejectsReusedSkuBeforeWriting() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(combinationRepository.findByProductId(PRODUCT_ID)).willReturn(List.of(existingCombination(REUSED_SKU)));

            assertThatThrownBy(() -> service.update(ruleCommand(null, null,
                    List.of(new CombinationOverrideCommand(List.of("s-m"), null, null, null, REUSED_SKU)))))
                    .isInstanceOf(InvalidProductOptionException.class);

            then(combinationRepository).should(never()).discontinueAllByProductId(any(), any());
            then(combinationRepository).should(never()).saveAll(any());
            then(productRepository).should(never()).save(any());
            then(productRepository).should(never()).replaceOptionTypes(any(), any());
            then(productRepository).should(never()).replaceOptionConfig(any(), any());
        }

        @Test
        @DisplayName("잘못된 룰(불완전 조합 오버라이드)이면 소유권 확인 뒤, 기존 조합을 건드리기 전에 거절한다")
        void rejectsInvalidRulesBeforeWriting() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            ProductOptionTypeCommand color = new ProductOptionTypeCommand("색상", null, (short) 2, List.of(
                    new ProductOptionValueCommand("빨강", null, null, 0, (short) 1, "c-red")));
            ProductOptionTypeCommand size = new ProductOptionTypeCommand("사이즈", null, (short) 1, List.of(
                    new ProductOptionValueCommand("S", null, null, 0, (short) 1, "s-s")));
            UpdateProductOptionsCommand partialOverride = new UpdateProductOptionsCommand(
                    PRODUCT_ID, SELLER_ID, List.of(size, color), 50, EXPECTED_VERSION, null, null,
                    List.of(new CombinationOverrideCommand(List.of("s-s"), 1_000, null, null, null)));

            assertThatThrownBy(() -> service.update(partialOverride))
                    .isInstanceOf(InvalidProductOptionException.class);

            then(combinationRepository).shouldHaveNoInteractions();
            then(productRepository).should(never()).save(any());
            then(productRepository).should(never()).replaceOptionTypes(any(), any());
        }
    }

    @Nested
    @DisplayName("옵션 검증 전파")
    class OptionValidation {

        @Test
        @DisplayName("값이 없는 옵션 타입이면 InvalidProductOptionException이 전파되고 조합 saveAll은 없다")
        void typeWithoutValuesPropagates() {
            ProductOptionTypeModel emptyType =
                    new ProductOptionTypeModel(UUID.randomUUID(), null, "색상", (short) 1, List.of());
            Product savedWithEmptyType = onSaleProduct(SELLER_ID).toBuilder().optionTypes(List.of(emptyType)).build();
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(productRepository.replaceOptionTypes(eq(PRODUCT_ID), any())).willReturn(savedWithEmptyType);

            ProductOptionTypeCommand colorWithoutValues =
                    new ProductOptionTypeCommand("색상", null, (short) 1, List.of());

            assertThatThrownBy(() -> service.update(
                    new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(colorWithoutValues), 50, EXPECTED_VERSION, null, null, null)))
                    .isInstanceOf(InvalidProductOptionException.class);

            then(combinationRepository).should(never()).saveAll(any());
        }
    }

    @Nested
    @DisplayName("실패 — 옵션을 건드리지 않는다")
    class Failure {

        @Test
        @DisplayName("상품이 없으면 ProductNotFoundException")
        void productMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductNotFoundException.class);

            then(productRepository).should(never()).save(any());
            then(productRepository).should(never()).replaceOptionTypes(any(), any());
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(OTHER_SELLER)));

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(productRepository).should(never()).save(any());
            then(productRepository).should(never()).replaceOptionTypes(any(), any());
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
            then(combinationRepository).shouldHaveNoInteractions();
        }
    }
}
