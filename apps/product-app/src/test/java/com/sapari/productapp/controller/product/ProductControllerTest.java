package com.sapari.productapp.controller.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.config.CommonWebMvcConfig;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.GetProductDetailCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.ProductDetailView;
import com.sapari.productapp.config.SecurityConfig;

/**
 * 공개 상품 상세 컨트롤러 슬라이스 테스트. 성공은 200 + 성공 봉투, 유스케이스가 숨긴 상품(미존재·삭제·비공개)은 404 fail 봉투로
 * 나가는지 고정한다. 비공개 상태를 숨기는 규칙 자체는 유스케이스({@code GetProductDetailServiceTest})가 검증한다.
 */
@WebMvcTest(controllers = ProductController.class)
@Import({
        ProductController.class,
        SecurityConfig.class,
        CommonWebMvcConfig.class,
        GlobalExceptionHandler.class,
        TimeProvider.class,
        ProductControllerTest.FixedClockConfig.class
})
@DisplayName("공개 상품 상세 컨트롤러 응답 계약")
class ProductControllerTest {

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

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
    private GetProductDetailUseCase getProductDetailUseCase;

    /**
     * 인증 없이 조회하면 200 + 성공 봉투로 유스케이스 결과를 그대로 담고, 경로의 productId로 조회한다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("200 + 성공 봉투(data=상세), 경로 productId로 조회한다")
    void okWithEnvelope() throws Exception {
        ProductDetailView view = FM.giveMeBuilder(ProductDetailView.class)
                .set("productId", PRODUCT_ID)
                .set("status", "ON_SALE")
                .set("name", "베이직 티셔츠")
                .sample();
        given(getProductDetailUseCase.get(any())).willReturn(view);

        mockMvc.perform(get("/api/v1/products/{id}", PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.name").value("베이직 티셔츠"))
                .andExpect(jsonPath("$.error").isEmpty());

        then(getProductDetailUseCase).should().get(new GetProductDetailCommand(PRODUCT_ID));
    }

    /**
     * 유스케이스가 숨긴 상품은 404 fail 봉투(PRODUCT-001)이며, 응답 메시지는 카탈로그 문구라 내부 id를 노출하지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("숨겨진 상품은 404 fail 봉투(PRODUCT-001), 메시지에 상품 id가 없다")
    void hiddenIsNotFound() throws Exception {
        given(getProductDetailUseCase.get(any()))
                .willThrow(new ProductNotFoundException("상품을 찾을 수 없습니다: " + PRODUCT_ID));

        mockMvc.perform(get("/api/v1/products/{id}", PRODUCT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("PRODUCT-001"))
                .andExpect(jsonPath("$.error.message").value("상품을 찾을 수 없습니다."));
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
