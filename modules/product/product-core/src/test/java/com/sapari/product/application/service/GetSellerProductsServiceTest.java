package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ImageRole;
import com.sapari.product.domain.model.product.Product;
import com.sapari.product.domain.model.product.ProductImageRef;
import com.sapari.product.domain.model.product.ProductOptionModel;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.view.ProductSummaryView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetSellerProductsService")
class GetSellerProductsServiceTest {

    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_1 = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID PRODUCT_2 = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final Long CATEGORY_ID = 1001L;
    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private GetSellerProductsService service;

    private static ProductImageRef image(UUID productId, ImageRole role, String key, short sortOrder) {
        return new ProductImageRef(UUID.randomUUID(), productId, null, role, key, sortOrder);
    }

    private static Product product(UUID id, Instant deletedAt, List<ProductImageRef> images) {
        return Product.create(
                        SELLER_ID, CATEGORY_ID, "상품 " + id, "설명", 10_000, null, 0, ProductOptionModel.COMBINATION, NOW)
                .approve(NOW)
                .toBuilder()
                .id(id)
                .minPrice(9_000)
                .hasStock(true)
                .avgRating(new BigDecimal("4.2"))
                .reviewCount(5)
                .deletedAt(deletedAt)
                .images(images)
                .build();
    }

    private List<ProductSummaryView> get() {
        return service.get(new GetSellerProductsCommand(SELLER_ID));
    }

    @Nested
    @DisplayName("목록 매핑")
    class Mapping {

        @Test
        @DisplayName("판매자 상품의 요약 필드를 매핑한다")
        void mapsSummaryFields() {
            Product product = product(PRODUCT_1, null,
                    List.of(image(PRODUCT_1, ImageRole.GALLERY, "g0.jpg", (short) 0)));
            given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(product));

            ProductSummaryView view = get().get(0);

            assertThat(view.productId()).isEqualTo(PRODUCT_1);
            assertThat(view.name()).isEqualTo("상품 " + PRODUCT_1);
            assertThat(view.status()).isEqualTo("ON_SALE");
            assertThat(view.minPrice()).isEqualTo(9_000);
            assertThat(view.hasStock()).isTrue();
            assertThat(view.avgRating()).isEqualByComparingTo("4.2");
            assertThat(view.reviewCount()).isEqualTo(5);
            assertThat(view.thumbnailKey()).isEqualTo("g0.jpg");
        }

        @Test
        @DisplayName("대표 이미지는 sortOrder가 가장 낮은 GALLERY 이미지이며 DETAIL은 제외한다")
        void thumbnailIsLowestSortGalleryImage() {
            Product product = product(PRODUCT_1, null, List.of(
                    image(PRODUCT_1, ImageRole.GALLERY, "g1.jpg", (short) 1),
                    image(PRODUCT_1, ImageRole.GALLERY, "g0.jpg", (short) 0),
                    image(PRODUCT_1, ImageRole.DETAIL, "d0.jpg", (short) 0)));
            given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(product));

            assertThat(get().get(0).thumbnailKey()).isEqualTo("g0.jpg");
        }

        @Test
        @DisplayName("GALLERY 이미지가 없으면 thumbnailKey는 null이다")
        void thumbnailNullWhenNoGallery() {
            Product product = product(PRODUCT_1, null,
                    List.of(image(PRODUCT_1, ImageRole.DETAIL, "d0.jpg", (short) 0)));
            given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(product));

            assertThat(get().get(0).thumbnailKey()).isNull();
        }
    }

    @Nested
    @DisplayName("필터링")
    class Filtering {

        @Test
        @DisplayName("소프트 삭제된 상품은 목록에서 제외한다")
        void excludesDeleted() {
            Product live = product(PRODUCT_1, null, List.of());
            Product deleted = product(PRODUCT_2, NOW, List.of());
            given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of(live, deleted));

            assertThat(get())
                    .extracting(ProductSummaryView::productId)
                    .containsExactly(PRODUCT_1);
        }

        @Test
        @DisplayName("삭제되지 않은 상품이 여러 개면 모두 반환한다")
        void returnsAllLiveProducts() {
            given(productRepository.findBySellerId(SELLER_ID))
                    .willReturn(List.of(product(PRODUCT_1, null, List.of()), product(PRODUCT_2, null, List.of())));

            assertThat(get())
                    .extracting(ProductSummaryView::productId)
                    .containsExactlyInAnyOrder(PRODUCT_1, PRODUCT_2);
        }

        @Test
        @DisplayName("판매자 상품이 없으면 빈 목록을 반환한다")
        void emptyWhenNone() {
            given(productRepository.findBySellerId(SELLER_ID)).willReturn(List.of());

            assertThat(get()).isEmpty();
        }

        @Test
        @DisplayName("삭제된 상품만 있으면 빈 목록을 반환한다")
        void emptyWhenAllDeleted() {
            given(productRepository.findBySellerId(SELLER_ID))
                    .willReturn(List.of(product(PRODUCT_1, NOW, List.of())));

            assertThat(get()).isEmpty();
        }
    }
}
