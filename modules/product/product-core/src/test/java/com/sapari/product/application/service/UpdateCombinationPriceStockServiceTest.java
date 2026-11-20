package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationUpdateCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.domain.exception.CombinationNotFoundException;
import com.sapari.product.domain.exception.InvalidProductStateException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.Product;
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
@DisplayName("UpdateCombinationPriceStockService")
class UpdateCombinationPriceStockServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID OTHER_PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID COMBO_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID COMBO_2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID MISSING_COMBO = UUID.fromString("00000000-0000-0000-0000-0000000000d9");
    private static final Long CATEGORY_ID = 1001L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");
    private static final Long EXPECTED_VERSION = 7L;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionCombinationRepository combinationRepository;

    // 실제 TimeProvider를 고정 Clock으로 — now()는 LATER를 결정적으로 반환(스텁 불필요)
    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(LATER, ZoneOffset.UTC));

    private UpdateCombinationPriceStockService service;

    @BeforeEach
    void setUp() {
        service = new UpdateCombinationPriceStockService(productRepository, combinationRepository, timeProvider);
    }

    private static Product product(UUID sellerId) {
        return Product.create(sellerId, CATEGORY_ID, "상품", "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW).toBuilder().id(PRODUCT_ID).build();
    }

    private static ProductOptionCombination combo(UUID productId) {
        return ProductOptionCombination.builder()
                .id(COMBO_ID)
                .productId(productId)
                .combinationKey(CombinationKey.of("k"))
                .sku(Sku.of("SKU-1"))
                .originalPrice(12_000)
                .price(10_000)
                .stock(Stock.of(100, 10))
                .isAvailable(true)
                .optionValueIds(List.of(UUID.randomUUID()))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static UpdateCombinationPriceStockCommand command(CombinationUpdateCommand... updates) {
        return new UpdateCombinationPriceStockCommand(PRODUCT_ID, SELLER_ID, List.of(updates));
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("가격·정가·재고·판매여부를 한꺼번에 적용하고 가용재고를 다시 계산한다")
        void appliesAllChanges() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, 8_900, 9_900, 50, false, EXPECTED_VERSION)));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should().save(captor.capture());
            ProductOptionCombination saved = captor.getValue();
            assertThat(saved.id()).isEqualTo(COMBO_ID);
            assertThat(saved.price()).isEqualTo(8_900);
            assertThat(saved.originalPrice()).isEqualTo(9_900);
            assertThat(saved.stock().stock()).isEqualTo(50);
            assertThat(saved.stock().reservedStock()).isEqualTo(10);
            assertThat(saved.availableStock()).isEqualTo(40);
            assertThat(saved.isAvailable()).isFalse();
            assertThat(saved.updatedAt()).isEqualTo(LATER);
            // 클라이언트가 본 조합 version으로 덮어써 조합별 stale 비교가 동작하도록 보장한다(§13)
            assertThat(saved.version()).isEqualTo(EXPECTED_VERSION);
        }

        @Test
        @DisplayName("재고만 지정하면 가격·판매여부는 그대로 둔다")
        void appliesStockOnly() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, null, null, 30, null, EXPECTED_VERSION)));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should().save(captor.capture());
            ProductOptionCombination saved = captor.getValue();
            assertThat(saved.stock().stock()).isEqualTo(30);
            assertThat(saved.price()).isEqualTo(10_000);
            assertThat(saved.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("price 없이 originalPrice만 지정하면 가격은 그대로 둔다 (price=null 분기)")
        void originalPriceWithoutPriceIsIgnored() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, null, 9_900, null, null, EXPECTED_VERSION)));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should().save(captor.capture());
            ProductOptionCombination saved = captor.getValue();
            assertThat(saved.price()).isEqualTo(10_000);
            assertThat(saved.originalPrice()).isEqualTo(12_000);
        }

        @Test
        @DisplayName("여러 조합을 한 커맨드로 각각 변경·저장한다")
        void updatesMultipleCombinations() {
            ProductOptionCombination combo2 = ProductOptionCombination.builder()
                    .id(COMBO_2)
                    .productId(PRODUCT_ID)
                    .combinationKey(CombinationKey.of("k2"))
                    .price(20_000)
                    .stock(Stock.of(10, 0))
                    .isAvailable(true)
                    .optionValueIds(List.of(UUID.randomUUID()))
                    .createdAt(NOW)
                    .updatedAt(NOW)
                    .build();
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(PRODUCT_ID), combo2));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(
                    new CombinationUpdateCommand(COMBO_ID, 8_000, null, null, null, EXPECTED_VERSION),
                    new CombinationUpdateCommand(COMBO_2, null, null, 5, null, EXPECTED_VERSION)));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should(times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(ProductOptionCombination::id)
                    .containsExactly(COMBO_ID, COMBO_2);
        }

        @Test
        @DisplayName("모든 조합을 저장한 뒤 상품 캐시(min_price·has_stock)를 한 번 재계산한다 (품절·재입고 반영)")
        void refreshesCacheOnceAfterSavingCombinations() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, null, null, 30, null, EXPECTED_VERSION)));

            InOrder inOrder = inOrder(combinationRepository, productRepository);
            inOrder.verify(combinationRepository).save(any());
            inOrder.verify(productRepository, times(1)).refreshStockPriceCache(PRODUCT_ID, LATER);
        }
    }

    @Nested
    @DisplayName("판매 재개(isAvailable=true) — 현재 옵션 구성의 조합만")
    class Reactivation {

        private final UUID currentValue = UUID.randomUUID();

        /** 옵션 타입 1개(값 currentValue)를 가진 판매자 상품. */
        private Product optionProduct() {
            ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 1,
                    List.of(new ProductOptionValueModel(currentValue, null, "M", null, 0, (short) 1)));
            return product(SELLER_ID).toBuilder().optionTypes(List.of(size)).build();
        }

        private ProductOptionCombination discontinuedCombo(String key, List<UUID> valueIds) {
            return combo(PRODUCT_ID).toBuilder()
                    .combinationKey(CombinationKey.of(key))
                    .optionValueIds(valueIds)
                    .isAvailable(false)
                    .build();
        }

        private CombinationUpdateCommand reactivate() {
            return new CombinationUpdateCommand(COMBO_ID, null, null, null, true, EXPECTED_VERSION);
        }

        @Test
        @DisplayName("판매자가 단종한 현재 옵션 조합은 다시 판매할 수 있다")
        void reactivatesSellerDiscontinuedCurrentCombination() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(optionProduct()));
            given(combinationRepository.findAllById(any()))
                    .willReturn(List.of(discontinuedCombo("k", List.of(currentValue))));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(reactivate()));

            ArgumentCaptor<ProductOptionCombination> captor = ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should().save(captor.capture());
            assertThat(captor.getValue().isAvailable()).isTrue();
        }

        @Test
        @DisplayName("옵션 교체로 은퇴한 조합(옛 옵션값 참조)은 판매를 재개할 수 없다 — PRODUCT-002, 저장·캐시 재계산 없음")
        void rejectsRetiredOptionCombination() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(optionProduct()));
            given(combinationRepository.findAllById(any()))
                    .willReturn(List.of(discontinuedCombo("k-old", List.of(UUID.randomUUID()))));

            assertThatThrownBy(() -> service.update(command(reactivate())))
                    .isInstanceOf(InvalidProductStateException.class);

            then(combinationRepository).should(never()).save(any());
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
        }

        @Test
        @DisplayName("옵션 상품으로 바뀐 뒤 남은 옛 기본 조합(옵션값 없음)은 판매를 재개할 수 없다")
        void rejectsRetiredDefaultOnOptionProduct() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(optionProduct()));
            given(combinationRepository.findAllById(any()))
                    .willReturn(List.of(discontinuedCombo(CombinationKey.DEFAULT + "#" + COMBO_ID, List.of())));

            assertThatThrownBy(() -> service.update(command(reactivate())))
                    .isInstanceOf(InvalidProductStateException.class);
        }

        @Test
        @DisplayName("단일 상품은 현재 기본 조합(DEFAULT)만 재개할 수 있고, 은퇴한 DEFAULT#id는 거절한다")
        void singleProductOnlyCurrentDefault() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(combinationRepository.findAllById(any()))
                    .willReturn(List.of(discontinuedCombo(CombinationKey.DEFAULT, List.of())))
                    .willReturn(List.of(discontinuedCombo(CombinationKey.DEFAULT + "#" + COMBO_ID, List.of())));

            service.update(command(reactivate()));
            assertThatThrownBy(() -> service.update(command(reactivate())))
                    .isInstanceOf(InvalidProductStateException.class);

            then(combinationRepository).should(times(1)).save(any());
        }

        @Test
        @DisplayName("판매여부를 바꾸지 않거나 단종하는 요청은 은퇴 조합에도 허용한다 (재개만 막는다)")
        void allowsNonReactivatingChangesOnRetiredCombination() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(optionProduct()));
            given(combinationRepository.findAllById(any()))
                    .willReturn(List.of(discontinuedCombo("k-old", List.of(UUID.randomUUID()))));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, 5_000, null, null, false, EXPECTED_VERSION)));

            then(combinationRepository).should().save(any());
        }
    }

    @Nested
    @DisplayName("잠금 순서")
    class LockOrder {

        @Test
        @DisplayName("요청 순서와 무관하게 조합 id 오름차순으로 저장한다 ([c2,c1]·[c1,c2] 동시 요청의 교착 방지)")
        void savesInCombinationIdOrder() {
            ProductOptionCombination combo2 = combo(PRODUCT_ID).toBuilder().id(COMBO_2).build();
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo2, combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(
                    new CombinationUpdateCommand(COMBO_2, null, null, 20, null, EXPECTED_VERSION),
                    new CombinationUpdateCommand(COMBO_ID, null, null, 30, null, EXPECTED_VERSION)));

            ArgumentCaptor<ProductOptionCombination> captor = ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should(times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).extracting(ProductOptionCombination::id)
                    .containsExactly(COMBO_ID, COMBO_2);
        }
    }

    @Nested
    @DisplayName("실패 — 저장하지 않는다")
    class Failure {

        @Test
        @DisplayName("상품이 없으면 ProductNotFoundException")
        void productMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null, EXPECTED_VERSION))))
                    .isInstanceOf(ProductNotFoundException.class);

            then(combinationRepository).shouldHaveNoInteractions();
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(OTHER_SELLER)));

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null, EXPECTED_VERSION))))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(combinationRepository).shouldHaveNoInteractions();
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
        }

        @Test
        @DisplayName("조합이 없으면 CombinationNotFoundException")
        void combinationMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of());

            assertThatThrownBy(() -> service.update(
                    command(new CombinationUpdateCommand(MISSING_COMBO, 100, null, null, null, EXPECTED_VERSION))))
                    .isInstanceOf(CombinationNotFoundException.class);

            then(combinationRepository).should(never()).save(any());
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
        }

        @Test
        @DisplayName("조합이 이 상품 소속이 아니면 CombinationNotFoundException")
        void combinationOfOtherProduct() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findAllById(any())).willReturn(List.of(combo(OTHER_PRODUCT)));

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null, EXPECTED_VERSION))))
                    .isInstanceOf(CombinationNotFoundException.class);

            then(combinationRepository).should(never()).save(any());
            then(productRepository).should(never()).refreshStockPriceCache(any(), any());
        }
    }
}
