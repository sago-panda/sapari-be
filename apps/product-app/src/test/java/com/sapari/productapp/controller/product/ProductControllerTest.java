package com.sapari.productapp.controller.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.ProductDetailView;

/**
 * {@link ProductController}의 공개 상세 상태 게이트(M-4) 단위 테스트.
 * 판매 가능(ON_SALE) 상품만 노출하고, 비공개 상태는 존재를 숨겨 404로 취급하는지 고정한다.
 */
class ProductControllerTest {

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    private final GetProductDetailUseCase getProductDetailUseCase = mock(GetProductDetailUseCase.class);
    private final ProductController productController = new ProductController(getProductDetailUseCase);

    /**
     * ON_SALE 상품은 200으로 상세를 그대로 노출하고, 조회 커맨드에 경로의 productId가 실린다.
     */
    @Test
    void ON_SALE_상품은_200으로_상세를_노출한다() {
        UUID productId = UUID.randomUUID();
        ProductDetailView view = FM.giveMeBuilder(ProductDetailView.class)
                .set("productId", productId)
                .set("status", "ON_SALE")
                .sample();
        when(getProductDetailUseCase.get(any(GetProductDetailCommand.class))).thenReturn(view);

        ResponseEntity<ProductDetailView> response = productController.getDetail(productId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(view);

        ArgumentCaptor<GetProductDetailCommand> captor = ArgumentCaptor.forClass(GetProductDetailCommand.class);
        verify(getProductDetailUseCase).get(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(productId);
    }

    /**
     * 비공개 상태(PENDING_REVIEW/SUSPENDED/REJECTED) 상품은 존재를 숨겨 404(PRODUCT-001)로 취급한다.
     *
     * @param status 노출되면 안 되는 비공개 상태명
     */
    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "SUSPENDED", "REJECTED"})
    void 비공개_상태_상품은_404로_숨긴다(String status) {
        UUID productId = UUID.randomUUID();
        ProductDetailView view = FM.giveMeBuilder(ProductDetailView.class)
                .set("productId", productId)
                .set("status", status)
                .sample();
        when(getProductDetailUseCase.get(any(GetProductDetailCommand.class))).thenReturn(view);

        assertThatThrownBy(() -> productController.getDetail(productId))
                .isInstanceOf(ProductNotFoundException.class);
    }
}
