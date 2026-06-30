package com.sapari.productapp.controller.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.sapari.global.time.TimeProvider;
import com.sapari.product.port.GetProductDetailUseCase;
import com.sapari.product.view.ProductDetailView;
import com.sapari.productapp.config.SecurityConfig;
import com.sapari.productapp.config.WebMvcConfig;

/**
 * 공개 필터 체인의 기본 거부(default-deny) 동작 테스트. 명시 허용된 공개 GET만 통과하고,
 * 비-GET·미허용 경로는 거부(401)되어 매처 누락으로 무인증 노출되지 않음을 고정한다.
 */
@WebMvcTest(controllers = ProductController.class)
@Import({
        SecurityConfig.class,
        WebMvcConfig.class,
        TimeProvider.class,
        PublicFilterChainSecurityTest.FixedClockConfig.class
})
@DisplayName("공개 필터 체인 기본 거부 테스트")
class PublicFilterChainSecurityTest {

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

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

    /**
     * 공개 상품 조회(GET)는 인증 없이 시큐리티를 통과한다(401/403이 아니다). 핸들러 실제 디스패치·성공 응답은
     * @WebMvcTest 슬라이스 밖이라(핸들러 미매핑 → 404) {@code ProductControllerTest}가 담당하고, 여기서는 인가 통과만 고정한다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("공개 상품 GET은 인증 없이 허용된다")
    void publicGetIsPermitted() throws Exception {
        ProductDetailView view = FM.giveMeBuilder(ProductDetailView.class)
                .set("status", "ON_SALE")
                .sample();
        when(getProductDetailUseCase.get(any())).thenReturn(view);

        int statusCode = mockMvc.perform(get("/api/v1/products/{id}", UUID.randomUUID()))
                .andReturn()
                .getResponse()
                .getStatus();

        assertThat(statusCode)
                .as("공개 GET은 시큐리티를 통과하므로 401/403이 아니어야 한다")
                .isNotIn(401, 403);
    }

    /**
     * 상품 경로의 비-GET 메서드는 허용 목록에 없어 기본 거부(401)된다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("상품 경로의 POST는 기본 거부된다(401)")
    void nonGetOnProductsIsDenied() throws Exception {
        mockMvc.perform(post("/api/v1/products/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 허용 목록에 없는 임의 경로는 기본 거부(401)된다 — default-deny 보장.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("허용 목록 밖 임의 경로는 기본 거부된다(401)")
    void unlistedPathIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/internal/secret"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 공개 GET 매처는 단일 세그먼트({@code /products/{id}})로 한정되므로, 하위 세그먼트 GET은 기본 거부(401)된다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("상품 하위 세그먼트 GET은 자동 공개되지 않고 거부된다(401)")
    void deeperProductSubpathIsDenied() throws Exception {
        mockMvc.perform(get("/api/v1/products/{id}/internal", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
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
