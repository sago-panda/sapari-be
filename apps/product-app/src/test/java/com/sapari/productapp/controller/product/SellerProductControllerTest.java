package com.sapari.productapp.controller.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.ConstructorPropertiesArbitraryIntrospector;
import com.sapari.common.page.CursorPage;
import com.sapari.common.page.InvalidCursorException;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.config.CommonWebMvcConfig;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.command.ChangeSaleStatusCommand;
import com.sapari.product.command.CreateProductCommand;
import com.sapari.product.command.DeleteProductCommand;
import com.sapari.product.command.GetSellerProductsCommand;
import com.sapari.product.command.UpdateCombinationPriceStockCommand;
import com.sapari.product.command.UpdateProductInfoCommand;
import com.sapari.product.command.UpdateProductOptionsCommand;
import com.sapari.product.domain.exception.ProductNotFoundException;
import com.sapari.product.port.ChangeProductSaleStatusUseCase;
import com.sapari.product.port.CreateProductUseCase;
import com.sapari.product.port.DeleteProductUseCase;
import com.sapari.product.port.GetSellerProductsUseCase;
import com.sapari.product.port.UpdateCombinationPriceStockUseCase;
import com.sapari.product.port.UpdateProductInfoUseCase;
import com.sapari.product.port.UpdateProductOptionsUseCase;
import com.sapari.product.view.CreateProductView;
import com.sapari.product.view.ProductSummaryView;
import com.sapari.productapp.config.SecurityConfig;

/**
 * 판매자 상품 컨트롤러 슬라이스 테스트. 응답 계약(성공은 {@code ResponseEnvelope} 봉투, 상태코드는 {@code @ResponseStatus}로
 * 201/200/204, 204는 본문 없음, 실패는 fail 봉투)과 인증 판매자·경로·쿼리 값이 커맨드로 정확히 실리는지 고정한다.
 * 인가(401/403)는 {@code SellerProductSecurityTest}, 유스케이스 동작은 각 서비스 테스트가 담당한다.
 */
@WebMvcTest(controllers = SellerProductController.class)
@Import({
        SellerProductController.class,
        SecurityConfig.class,
        CommonWebMvcConfig.class,
        GlobalExceptionHandler.class,
        TimeProvider.class,
        SellerProductControllerTest.FixedClockConfig.class
})
@DisplayName("판매자 상품 컨트롤러 응답 계약")
class SellerProductControllerTest {

    private static final String BASE_PATH = "/api/v1/seller/products";
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID COMBINATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final FixtureMonkey FM = FixtureMonkey.builder()
            .objectIntrospector(ConstructorPropertiesArbitraryIntrospector.INSTANCE)
            .defaultNotNull(true)
            .build();

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
     * 인증 판매자로 JSON 본문을 보내는 요청을 만든다.
     */
    private static MockHttpServletRequestBuilder asSeller(MockHttpServletRequestBuilder request, String json) {
        return request.with(user(SELLER_ID.toString()).roles("SELLER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    /**
     * 등록은 201 + 성공 봉투이고, 인증 판매자 id와 본문이 커맨드로 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("201 + 성공 봉투(data=생성 결과), 커맨드에 인증 판매자 id·본문 값이 실린다")
    void createdWithEnvelope() throws Exception {
        given(createProductUseCase.create(any())).willReturn(new CreateProductView(PRODUCT_ID, "PENDING_REVIEW"));

        mockMvc.perform(asSeller(post(BASE_PATH), """
                        {"categoryId": 1001, "name": "베이직 티셔츠", "basePrice": 10000, "defaultStock": 5}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.error").isEmpty());

        ArgumentCaptor<CreateProductCommand> captor = ArgumentCaptor.forClass(CreateProductCommand.class);
        then(createProductUseCase).should().create(captor.capture());
        assertThat(captor.getValue().sellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().categoryId()).isEqualTo(1001L);
        assertThat(captor.getValue().name()).isEqualTo("베이직 티셔츠");
        assertThat(captor.getValue().basePrice()).isEqualTo(10_000);
        assertThat(captor.getValue().defaultStock()).isEqualTo(5);
    }

    /**
     * 목록은 200 + 성공 봉투 안에 커서 페이지(content·nextCursor·hasNext)를 담는다(봉투 중첩 없음).
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("200 + 성공 봉투 안에 커서 페이지(content·nextCursor·hasNext)를 그대로 담는다")
    void okWithCursorPage() throws Exception {
        ProductSummaryView summary = FM.giveMeBuilder(ProductSummaryView.class)
                .set("productId", PRODUCT_ID)
                .set("status", "ON_SALE")
                .sample();
        given(getSellerProductsUseCase.get(any())).willReturn(new CursorPage<>(List.of(summary), "next-cursor", true));

        mockMvc.perform(get(BASE_PATH).with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.data.content[0].status").value("ON_SALE"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.success").doesNotExist());
    }

    /**
     * 마지막 페이지는 nextCursor가 명시적 null이다(필드 생략이 아니다).
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("마지막 페이지는 nextCursor=null·hasNext=false로 내려간다")
    void lastPage() throws Exception {
        given(getSellerProductsUseCase.get(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(get(BASE_PATH).with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.nextCursor").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    /**
     * cursor·size 쿼리와 인증 판매자 id가 커맨드로 그대로 실린다(정규화·디코딩은 서비스 책임).
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("cursor·size 쿼리를 커맨드로 그대로 넘긴다(정규화는 서비스 책임)")
    void passesCursorAndSize() throws Exception {
        given(getSellerProductsUseCase.get(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(get(BASE_PATH).param("cursor", "abc").param("size", "30")
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isOk());

        then(getSellerProductsUseCase).should().get(new GetSellerProductsCommand(SELLER_ID, "abc", 30));
    }

    /**
     * 쿼리를 생략하면 첫 페이지·기본 크기 요청(cursor=null, size=null)이다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("쿼리를 생략하면 cursor=null·size=null로 넘긴다")
    void omitsQuery() throws Exception {
        given(getSellerProductsUseCase.get(any())).willReturn(new CursorPage<>(List.of(), null, false));

        mockMvc.perform(get(BASE_PATH).with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isOk());

        then(getSellerProductsUseCase).should().get(new GetSellerProductsCommand(SELLER_ID, null, null));
    }

    /**
     * 숫자가 아닌 size는 바인딩 단계에서 400이며 유스케이스를 부르지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("숫자가 아닌 size는 400, 유스케이스를 호출하지 않는다")
    void rejectsNonNumericSize() throws Exception {
        mockMvc.perform(get(BASE_PATH).param("size", "many")
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        then(getSellerProductsUseCase).shouldHaveNoInteractions();
    }

    /**
     * 손상된 커서(유스케이스가 던지는 InvalidCursorException)는 400 fail 봉투(COMMON-001)다 — 500이 아니다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("손상된 커서는 400 fail 봉투(COMMON-001)다")
    void invalidCursorIsBadRequest() throws Exception {
        given(getSellerProductsUseCase.get(any())).willThrow(new InvalidCursorException("커서 Base64 디코딩 실패"));

        mockMvc.perform(get(BASE_PATH).param("cursor", "!!")
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("COMMON-001"));
    }

    /**
     * 기본 정보 수정은 204·본문 없음이고, 경로 productId·인증 판매자 id·expectedVersion이 커맨드로 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("PUT 기본 정보 → 204·본문 없음, 커맨드에 productId·sellerId·expectedVersion")
    void updateInfo() throws Exception {
        mockMvc.perform(asSeller(put(BASE_PATH + "/{id}", PRODUCT_ID), """
                        {"categoryId": 1001, "name": "새 이름", "expectedVersion": 3}
                        """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<UpdateProductInfoCommand> captor = ArgumentCaptor.forClass(UpdateProductInfoCommand.class);
        then(updateProductInfoUseCase).should().update(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().sellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().name()).isEqualTo("새 이름");
        assertThat(captor.getValue().expectedVersion()).isEqualTo(3L);
    }

    /**
     * 옵션 교체는 204·본문 없음이고, 경로 productId·인증 판매자 id·defaultStock·expectedVersion이 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("PUT 옵션 교체 → 204·본문 없음, 커맨드에 productId·sellerId·defaultStock·expectedVersion")
    void updateOptions() throws Exception {
        mockMvc.perform(asSeller(put(BASE_PATH + "/{id}/options", PRODUCT_ID), """
                        {"optionTypes": [], "defaultStock": 7, "expectedVersion": 4}
                        """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<UpdateProductOptionsCommand> captor = ArgumentCaptor.forClass(UpdateProductOptionsCommand.class);
        then(updateProductOptionsUseCase).should().update(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().sellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().defaultStock()).isEqualTo(7);
        assertThat(captor.getValue().expectedVersion()).isEqualTo(4L);
    }

    /**
     * 조합 가격·재고 수정은 204·본문 없음이고, 조합별 값이 커맨드로 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("PATCH 조합 가격·재고 → 204·본문 없음, 커맨드에 productId·sellerId·조합 값")
    void updateCombinations() throws Exception {
        mockMvc.perform(asSeller(patch(BASE_PATH + "/{id}/combinations", PRODUCT_ID), """
                        {"combinations": [{"combinationId": "%s", "price": 12000, "stock": 3, "expectedVersion": 2}]}
                        """.formatted(COMBINATION_ID)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<UpdateCombinationPriceStockCommand> captor =
                ArgumentCaptor.forClass(UpdateCombinationPriceStockCommand.class);
        then(updateCombinationPriceStockUseCase).should().update(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().sellerId()).isEqualTo(SELLER_ID);
        assertThat(captor.getValue().combinations()).singleElement().satisfies(combination -> {
            assertThat(combination.combinationId()).isEqualTo(COMBINATION_ID);
            assertThat(combination.price()).isEqualTo(12_000);
            assertThat(combination.stock()).isEqualTo(3);
            assertThat(combination.expectedVersion()).isEqualTo(2L);
        });
    }

    /**
     * 판매 상태 전환은 204·본문 없음이고, 전환 대상·expectedVersion이 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("PATCH 판매 상태 → 204·본문 없음, 커맨드에 전환 대상·expectedVersion")
    void changeStatus() throws Exception {
        mockMvc.perform(asSeller(patch(BASE_PATH + "/{id}/status", PRODUCT_ID), """
                        {"target": "SUSPENDED", "expectedVersion": 5}
                        """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        then(changeProductSaleStatusUseCase).should()
                .change(new ChangeSaleStatusCommand(PRODUCT_ID, SELLER_ID, "SUSPENDED", 5L));
    }

    /**
     * 삭제는 204·본문 없음이고, 쿼리 expectedVersion이 커맨드로 실린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("DELETE → 204·본문 없음, 쿼리 expectedVersion이 커맨드로 실린다")
    void deleteProduct() throws Exception {
        mockMvc.perform(delete(BASE_PATH + "/{id}", PRODUCT_ID).param("expectedVersion", "6")
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        then(deleteProductUseCase).should().delete(new DeleteProductCommand(PRODUCT_ID, SELLER_ID, 6L));
    }

    /**
     * 204 엔드포인트도 실패는 던져진 도메인 예외의 상태 + fail 봉투로 나간다(성공 형태만 본문 없음).
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("204 엔드포인트의 실패는 도메인 예외 상태(404) + fail 봉투다")
    void failureIsEnveloped() throws Exception {
        willThrow(new ProductNotFoundException("상품을 찾을 수 없습니다: " + PRODUCT_ID))
                .given(deleteProductUseCase).delete(any());

        mockMvc.perform(delete(BASE_PATH + "/{id}", PRODUCT_ID).param("expectedVersion", "6")
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("PRODUCT-001"));
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
