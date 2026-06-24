package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.CombinationUpdateCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.domain.exception.CombinationNotFoundException;
import com.sapari.product.domain.exception.ProductAccessDeniedException;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductOptionModel;
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
            given(combinationRepository.findById(COMBO_ID)).willReturn(Optional.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, 8_900, 9_900, 50, false)));

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
        }

        @Test
        @DisplayName("재고만 지정하면 가격·판매여부는 그대로 둔다")
        void appliesStockOnly() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findById(COMBO_ID)).willReturn(Optional.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, null, null, 30, null)));

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
            given(combinationRepository.findById(COMBO_ID)).willReturn(Optional.of(combo(PRODUCT_ID)));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(new CombinationUpdateCommand(COMBO_ID, null, 9_900, null, null)));

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
            given(combinationRepository.findById(COMBO_ID)).willReturn(Optional.of(combo(PRODUCT_ID)));
            given(combinationRepository.findById(COMBO_2)).willReturn(Optional.of(combo2));
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(command(
                    new CombinationUpdateCommand(COMBO_ID, 8_000, null, null, null),
                    new CombinationUpdateCommand(COMBO_2, null, null, 5, null)));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should(times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(ProductOptionCombination::id)
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

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null))))
                    .isInstanceOf(ProductNotFoundException.class);

            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(OTHER_SELLER)));

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null))))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("조합이 없으면 CombinationNotFoundException")
        void combinationMissing() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findById(MISSING_COMBO)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(
                    command(new CombinationUpdateCommand(MISSING_COMBO, 100, null, null, null))))
                    .isInstanceOf(CombinationNotFoundException.class);

            then(combinationRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("조합이 이 상품 소속이 아니면 CombinationNotFoundException")
        void combinationOfOtherProduct() {
            given(productRepository.findActiveById(PRODUCT_ID)).willReturn(Optional.of(product(SELLER_ID)));
            given(combinationRepository.findById(COMBO_ID)).willReturn(Optional.of(combo(OTHER_PRODUCT)));

            assertThatThrownBy(() -> service.update(command(new CombinationUpdateCommand(COMBO_ID, 100, null, null, null))))
                    .isInstanceOf(CombinationNotFoundException.class);

            then(combinationRepository).should(never()).save(any());
        }
    }
}
