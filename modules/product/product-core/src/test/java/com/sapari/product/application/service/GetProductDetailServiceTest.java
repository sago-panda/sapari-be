package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.domain.model.combination.CombinationKey;
import com.sapari.product.domain.model.combination.ProductOptionCombination;
import com.sapari.product.domain.model.combination.Sku;
import com.sapari.product.domain.model.combination.Stock;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.model.product.ProductOptionTypeModel;
import com.sapari.product.domain.model.product.ProductOptionValueModel;
import com.sapari.product.domain.repository.combination.ProductOptionCombinationRepository;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.view.CombinationView;
import com.sapari.product.view.ProductDetailView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetProductDetailService")
class GetProductDetailServiceTest {

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID IMAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID S_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID M_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID COMBO_S_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID COMBO_M_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d2");
    private static final Long CATEGORY_ID = 1001L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionCombinationRepository combinationRepository;

    @InjectMocks
    private GetProductDetailService service;

    private static Product onSaleProduct(Instant deletedAt) {
        ProductOptionValueModel s = new ProductOptionValueModel(S_ID, null, "S", null, 0, (short) 1);
        ProductOptionValueModel m = new ProductOptionValueModel(M_ID, null, "M", "{\"k\":1}", 1_000, (short) 2);
        ProductOptionTypeModel size = new ProductOptionTypeModel(UUID.randomUUID(), null, "사이즈", (short) 1, List.of(s, m));
        ProductImageRef image = new ProductImageRef(IMAGE_ID, PRODUCT_ID, null, ImageRole.GALLERY, "img/g1.jpg", (short) 0);
        return Product.create(
                        SELLER_ID, CATEGORY_ID, "베이직 티셔츠", "상세 설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW)
                .toBuilder()
                .id(PRODUCT_ID)
                .minPrice(10_000)
                .hasStock(true)
                .avgRating(new BigDecimal("4.5"))
                .reviewCount(12)
                .deletedAt(deletedAt)
                .tags(List.of("여름"))
                .images(List.of(image))
                .optionTypes(List.of(size))
                .build();
    }

    private static ProductOptionCombination combo(UUID id, UUID valueId, int price) {
        return ProductOptionCombination.builder()
                .id(id)
                .productId(PRODUCT_ID)
                .combinationKey(CombinationKey.of(valueId.toString()))
                .sku(Sku.of("SKU-" + price))
                .originalPrice(price + 2_000)
                .price(price)
                .stock(Stock.of(100, 10))
                .isAvailable(true)
                .optionValueIds(List.of(valueId))
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    @Nested
    @DisplayName("조회 성공")
    class Found {

        @Test
        @DisplayName("상품 기본 정보·태그·이미지를 뷰로 매핑한다")
        void mapsProductScalarsTagsImages() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(null)));
            given(combinationRepository.findByProductId(PRODUCT_ID))
                    .willReturn(List.of(combo(COMBO_S_ID, S_ID, 10_000)));

            ProductDetailView view = service.get(new GetProductDetailCommand(PRODUCT_ID));

            assertThat(view.productId()).isEqualTo(PRODUCT_ID);
            assertThat(view.sellerId()).isEqualTo(SELLER_ID);
            assertThat(view.categoryId()).isEqualTo(CATEGORY_ID);
            assertThat(view.name()).isEqualTo("베이직 티셔츠");
            assertThat(view.description()).isEqualTo("상세 설명");
            assertThat(view.basePrice()).isEqualTo(10_000);
            assertThat(view.status()).isEqualTo("ON_SALE");
            assertThat(view.minPrice()).isEqualTo(10_000);
            assertThat(view.hasStock()).isTrue();
            assertThat(view.avgRating()).isEqualByComparingTo("4.5");
            assertThat(view.reviewCount()).isEqualTo(12);
            assertThat(view.tags()).containsExactly("여름");
            assertThat(view.images()).singleElement().satisfies(img -> {
                assertThat(img.imageId()).isEqualTo(IMAGE_ID);
                assertThat(img.role()).isEqualTo("GALLERY");
                assertThat(img.optionValueId()).isNull();
                assertThat(img.imageKey()).isEqualTo("img/g1.jpg");
            });
        }

        @Test
        @DisplayName("옵션 타입/값을 중첩 뷰로 매핑한다")
        void mapsOptionTree() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(null)));
            given(combinationRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailView view = service.get(new GetProductDetailCommand(PRODUCT_ID));

            assertThat(view.optionTypes()).singleElement().satisfies(type -> {
                assertThat(type.name()).isEqualTo("사이즈");
                assertThat(type.sortOrder()).isEqualTo((short) 1);
                assertThat(type.values()).extracting(v -> v.optionValueId()).containsExactly(S_ID, M_ID);
                assertThat(type.values()).extracting(v -> v.value()).containsExactly("S", "M");
                assertThat(type.values()).extracting(v -> v.priceDelta()).containsExactly(0, 1_000);
            });
        }

        @Test
        @DisplayName("조합을 뷰로 매핑하고 가용재고(stock-reserved)·sku를 계산·노출한다")
        void mapsCombinationsWithAvailableStock() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(null)));
            given(combinationRepository.findByProductId(PRODUCT_ID))
                    .willReturn(List.of(combo(COMBO_S_ID, S_ID, 10_000), combo(COMBO_M_ID, M_ID, 11_000)));

            ProductDetailView view = service.get(new GetProductDetailCommand(PRODUCT_ID));

            assertThat(view.combinations()).hasSize(2);
            CombinationView first = view.combinations().get(0);
            assertThat(first.combinationId()).isEqualTo(COMBO_S_ID);
            assertThat(first.combinationKey()).isEqualTo(S_ID.toString());
            assertThat(first.optionValueIds()).containsExactly(S_ID);
            assertThat(first.price()).isEqualTo(10_000);
            assertThat(first.originalPrice()).isEqualTo(12_000);
            assertThat(first.stock()).isEqualTo(100);
            assertThat(first.availableStock()).isEqualTo(90);
            assertThat(first.isAvailable()).isTrue();
            assertThat(first.sku()).isEqualTo("SKU-10000");
        }

        @Test
        @DisplayName("조합이 없으면 combinations는 빈 목록이다")
        void emptyCombinations() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(null)));
            given(combinationRepository.findByProductId(PRODUCT_ID)).willReturn(List.of());

            ProductDetailView view = service.get(new GetProductDetailCommand(PRODUCT_ID));

            assertThat(view.combinations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("조회 실패")
    class NotFound {

        @Test
        @DisplayName("상품이 없으면 ProductNotFoundException, 조합은 조회하지 않는다")
        void rejectsWhenMissing() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.get(new GetProductDetailCommand(PRODUCT_ID)))
                    .isInstanceOf(ProductNotFoundException.class);

            then(combinationRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("소프트 삭제된 상품은 없는 것으로 간주해 ProductNotFoundException")
        void rejectsWhenDeleted() {
            given(productRepository.findById(PRODUCT_ID)).willReturn(Optional.of(onSaleProduct(NOW)));

            assertThatThrownBy(() -> service.get(new GetProductDetailCommand(PRODUCT_ID)))
                    .isInstanceOf(ProductNotFoundException.class);

            then(combinationRepository).shouldHaveNoInteractions();
        }
    }
}
