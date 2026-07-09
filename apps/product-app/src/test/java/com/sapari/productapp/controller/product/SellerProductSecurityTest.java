package com.sapari.productapp.controller.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.port.ChangeProductSaleStatusUseCase;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.port.DeleteProductUseCase;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.port.UpdateCombinationPriceStockUseCase;
import com.sapari.product.port.UpdateProductInfoUseCase;
import com.sapari.product.port.UpdateProductOptionsUseCase;
import com.sapari.productapp.config.SecurityConfig;
import com.sapari.productapp.config.WebMvcConfig;

/**
 * 판매자 상품 경로 시큐리티 체인 인가 테스트. {@code /api/v1/seller/**}가 미인증 401, 비-SELLER 403,
 * SELLER 200으로 보호되는지 고정한다(보안 민감 동작 회귀 방지).
 */
@WebMvcTest(controllers = SellerProductController.class)
@Import({
        SecurityConfig.class,
        WebMvcConfig.class,
        TimeProvider.class,
        SellerProductSecurityTest.FixedClockConfig.class
})
@DisplayName("판매자 상품 경로 인증/인가 테스트")
class SellerProductSecurityTest {

    private static final String SELLER_PRODUCTS_PATH = "/api/v1/seller/products";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AccessTokenRevocationChecker accessTokenRevocationChecker;

    @MockitoBean
    private SessionRevocationChecker sessionRevocationChecker;

    @MockitoBean
    private CreateProductUseCase createProductUseCase;

    @MockitoBean
    private GetSellerProductsUseCase getSellerProductsUseCase;

    @MockitoBean
    private UpdateProductInfoUseCase updateProductInfoUseCase;

    @MockitoBean
    private UpdateProductOptionsUseCase updateProductOptionsUseCase;

    @MockitoBean
    private UpdateCombinationPriceStockUseCase updateCombinationPriceStockUseCase;

    @MockitoBean
    private ChangeProductSaleStatusUseCase changeProductSaleStatusUseCase;

    @MockitoBean
    private DeleteProductUseCase deleteProductUseCase;

    /**
     * 토큰 없이 판매자 목록을 호출하면 401 ErrorResponse를 반환한다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("미인증 요청은 401을 반환한다")
    void unauthenticatedReturnsUnauthorized() throws Exception {
        mockMvc.perform(get(SELLER_PRODUCTS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(401));
    }

    /**
     * SELLER가 아닌 권한으로 판매자 목록을 호출하면 403 ErrorResponse를 반환한다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("비-SELLER 권한 요청은 403을 반환한다")
    void nonSellerReturnsForbidden() throws Exception {
        mockMvc.perform(get(SELLER_PRODUCTS_PATH)
                        .with(user(UUID.randomUUID().toString()).roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(403));
    }

    /**
     * SELLER 권한 요청은 인가를 통과한다(401/403이 아니다). 핸들러 실제 디스패치·성공 응답은 슬라이스 밖이라
     * {@code ProductControllerTest} 등 단위 테스트가 담당하고, 여기서는 인가 통과만 고정한다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("SELLER 권한 요청은 인가를 통과한다")
    void sellerPassesAuthorization() throws Exception {
        when(getSellerProductsUseCase.get(any())).thenReturn(List.of());

        int statusCode = mockMvc.perform(get(SELLER_PRODUCTS_PATH)
                        .with(user(UUID.randomUUID().toString()).roles("SELLER")))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(statusCode)
                .as("SELLER는 시큐리티 체인을 통과하므로 401/403이 아니어야 한다")
                .isNotIn(401, 403);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {

        /**
         * 결정적 응답 타임스탬프를 위한 고정 시계.
         *
         * @return 고정 시계
         */
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @SpringBootConfiguration
    static class TestApplication {
    }
}
