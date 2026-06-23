package com.sapari.product.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.domain.model.product.ProductStatus;
import com.sapari.product.domain.model.product.ProductSummary;
import com.sapari.product.domain.repository.product.ProductRepository;
import com.sapari.product.view.ProductSummaryView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private GetSellerProductsService service;

    private static ProductSummary summary(UUID id, String thumbnailKey) {
        return new ProductSummary(id, "상품 " + id, ProductStatus.ON_SALE, 9_000, true, 5, new BigDecimal("4.2"),
                thumbnailKey);
    }

    private List<ProductSummaryView> get() {
        return service.get(new GetSellerProductsCommand(SELLER_ID));
    }

    @Test
    @DisplayName("요약 read-model을 목록 뷰로 매핑한다(상태 enum은 이름 문자열)")
    void mapsSummaryToView() {
        given(productRepository.findActiveSellerProductSummaries(SELLER_ID))
                .willReturn(List.of(summary(PRODUCT_1, "g0.jpg")));

        assertThat(get()).singleElement().satisfies(view -> {
            assertThat(view.productId()).isEqualTo(PRODUCT_1);
            assertThat(view.name()).isEqualTo("상품 " + PRODUCT_1);
            assertThat(view.status()).isEqualTo("ON_SALE");
            assertThat(view.minPrice()).isEqualTo(9_000);
            assertThat(view.hasStock()).isTrue();
            assertThat(view.reviewCount()).isEqualTo(5);
            assertThat(view.avgRating()).isEqualByComparingTo("4.2");
            assertThat(view.thumbnailKey()).isEqualTo("g0.jpg");
        });
    }

    @Test
    @DisplayName("nullable 값(thumbnailKey·minPrice·avgRating)은 그대로 전달한다")
    void passesThroughNullables() {
        given(productRepository.findActiveSellerProductSummaries(SELLER_ID))
                .willReturn(List.of(new ProductSummary(
                        PRODUCT_1, "상품", ProductStatus.ON_SALE, null, false, 0, null, null)));

        ProductSummaryView view = get().get(0);
        assertThat(view.thumbnailKey()).isNull();
        assertThat(view.minPrice()).isNull();
        assertThat(view.avgRating()).isNull();
    }

    @Test
    @DisplayName("여러 건은 repository 반환 순서를 보존해 매핑한다")
    void preservesOrder() {
        given(productRepository.findActiveSellerProductSummaries(SELLER_ID))
                .willReturn(List.of(summary(PRODUCT_1, "g1.jpg"), summary(PRODUCT_2, "g2.jpg")));

        assertThat(get()).extracting(ProductSummaryView::productId).containsExactly(PRODUCT_1, PRODUCT_2);
    }

    @Test
    @DisplayName("요약이 없으면 빈 목록을 반환한다")
    void emptyWhenNone() {
        given(productRepository.findActiveSellerProductSummaries(SELLER_ID)).willReturn(List.of());

        assertThat(get()).isEmpty();
    }
}
