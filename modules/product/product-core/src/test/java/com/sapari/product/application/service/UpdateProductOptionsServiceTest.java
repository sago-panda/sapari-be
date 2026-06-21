package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.ProductOptionTypeCommand;
import com.sapari.product.command.ProductOptionValueCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;
import com.sapari.product.domain.exception.InvalidProductOptionException;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("UpdateProductOptionsService")
class UpdateProductOptionsServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_SELLER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final Long CATEGORY_ID = 1001L;
    private static final UUID OLD_COMBO_1 = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID OLD_COMBO_2 = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final UUID OLD_S = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID OLD_M = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID NEW_S = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID NEW_M = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant LATER = Instant.parse("2026-06-22T00:00:00Z");

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

    /** productRepository.save가 돌려줄, 새 옵션값 id가 부여된 저장본. */
    private static Product savedWithNewOptions() {
        ProductOptionValueModel s = new ProductOptionValueModel(NEW_S, null, "S", null, 0, (short) 1);
        ProductOptionValueModel m = new ProductOptionValueModel(NEW_M, null, "M", null, 0, (short) 2);
        ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 1, List.of(s, m));
        return onSaleProduct(SELLER_ID).toBuilder().optionTypes(List.of(size)).build();
    }

    private static ProductOptionCombination oldCombo(UUID id, UUID valueId) {
        return ProductOptionCombination.builder()
                .id(id)
                .productId(PRODUCT_ID)
                .combinationKey(CombinationKey.of(valueId.toString()))
                .sku(Sku.of("OLD-" + valueId))
                .price(10_000)
                .stock(Stock.of(100, 0))
                .isAvailable(true)
                .optionValueIds(List.of(valueId))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static UpdateProductOptionsCommand command() {
        ProductOptionValueCommand s = new ProductOptionValueCommand("S", null, null, 0, (short) 1);
        ProductOptionValueCommand m = new ProductOptionValueCommand("M", null, null, 0, (short) 2);
        ProductOptionTypeCommand size = new ProductOptionTypeCommand("사이즈", null, (short) 1, List.of(s, m));
        return new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(size), 50);
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
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(combinationRepository.findByProductId(PRODUCT_ID))
                    .willReturn(List.of(oldCombo(OLD_COMBO_1, OLD_S), oldCombo(OLD_COMBO_2, OLD_M)));
            given(productRepository.save(any())).willReturn(savedWithNewOptions());
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));
            given(combinationRepository.saveAll(any())).willAnswer(invocation -> invocation.getArgument(0));
        }

        @Test
        @DisplayName("새 옵션 타입으로 상품을 저장한다 (영속 전이라 옵션값 id는 null)")
        void savesProductWithNewOptions() {
            stubHappyPath();

            service.update(command());

            ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
            then(productRepository).should().save(productCaptor.capture());
            assertThat(productCaptor.getValue().optionTypes()).singleElement()
                    .satisfies(type -> {
                        assertThat(type.name()).isEqualTo("사이즈");
                        assertThat(type.values()).extracting(ProductOptionValueModel::value).containsExactly("S", "M");
                        assertThat(type.values()).extracting(ProductOptionValueModel::id).containsOnlyNulls();
                    });
        }

        @Test
        @DisplayName("기존 조합 2건은 save로 단종(isAvailable=false) 처리한다")
        void retiresExistingCombinations() {
            stubHappyPath();

            service.update(command());

            ArgumentCaptor<ProductOptionCombination> retireCaptor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should(times(2)).save(retireCaptor.capture());
            assertThat(retireCaptor.getAllValues())
                    .extracting(ProductOptionCombination::id)
                    .containsExactlyInAnyOrder(OLD_COMBO_1, OLD_COMBO_2);
            assertThat(retireCaptor.getAllValues())
                    .allSatisfy(c -> assertThat(c.isAvailable()).isFalse());
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
        @DisplayName("옵션을 비우면 기존 조합은 모두 단종하고 새 조합 saveAll은 호출하지 않는다 (옵션 없는 단일 상품)")
        void emptyOptionsRetiresAllAndCreatesNone() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(combinationRepository.findByProductId(PRODUCT_ID))
                    .willReturn(List.of(oldCombo(OLD_COMBO_1, OLD_S), oldCombo(OLD_COMBO_2, OLD_M)));
            given(productRepository.save(any())).willReturn(onSaleProduct(SELLER_ID)); // 옵션 없는 저장본
            given(combinationRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

            service.update(new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(), 50));

            ArgumentCaptor<ProductOptionCombination> captor =
                    ArgumentCaptor.forClass(ProductOptionCombination.class);
            then(combinationRepository).should(times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(c -> assertThat(c.isAvailable()).isFalse())
                    .extracting(ProductOptionCombination::id)
                    .containsExactlyInAnyOrder(OLD_COMBO_1, OLD_COMBO_2);
            then(combinationRepository).should(never()).saveAll(any());
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
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(SELLER_ID)));
            given(combinationRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());
            given(productRepository.save(any())).willReturn(savedWithEmptyType);

            ProductOptionTypeCommand colorWithoutValues =
                    new ProductOptionTypeCommand("색상", null, (short) 1, List.of());

            assertThatThrownBy(() -> service.update(
                    new UpdateProductOptionsCommand(PRODUCT_ID, SELLER_ID, List.of(colorWithoutValues), 50)))
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
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductNotFoundException.class);

            then(productRepository).should(never()).save(any());
            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("소프트 삭제된 상품은 ProductNotFoundException")
        void productDeleted() {
            given(productRepository.findById(PRODUCT_ID))
                    .willReturn(Optional.of(onSaleProduct(SELLER_ID).softDelete(NOW)));

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductNotFoundException.class);

            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 판매자의 상품이면 ProductAccessDeniedException")
        void notOwner() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(OTHER_SELLER)));

            assertThatThrownBy(() -> service.update(command()))
                    .isInstanceOf(ProductAccessDeniedException.class);

            then(productRepository).should(never()).save(any());
            then(combinationRepository).shouldHaveNoInteractions();
        }
    }
}
