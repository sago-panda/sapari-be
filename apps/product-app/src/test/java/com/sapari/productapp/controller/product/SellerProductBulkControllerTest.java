package com.sapari.productapp.controller.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.store.AccessTokenRevocationChecker;
import com.sapari.common.securityjwt.store.SessionRevocationChecker;
import com.sapari.common.web.config.CommonWebMvcConfig;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.domain.exception.BulkUploadRateLimitedException;
import com.sapari.product.domain.exception.BulkUploadUnavailableException;
import com.sapari.product.domain.exception.InvalidBulkUploadException;
import com.sapari.product.domain.exception.ProductErrorCode;
import com.sapari.productapp.bulk.BulkProductUploadProcessor;
import com.sapari.productapp.config.SecurityConfig;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse;
import com.sapari.productapp.controller.product.dto.response.BulkProductUploadResponse.UploadError;
import com.sapari.productapp.exception.BulkUploadExceptionHandler;

/**
 * 대량 등록 업로드 컨트롤러 슬라이스 테스트. 판매자 체인 인가(401/403), multipart 바인딩·인증 판매자 전달, 응답 봉투 형태,
 * 파일 거절(400 PRODUCT-012) 매핑을 고정한다. 엑셀 해석·검증은 {@code BulkProductUploadProcessorTest}가 담당한다.
 */
@WebMvcTest(controllers = SellerProductBulkController.class)
@Import({
        SellerProductBulkController.class,
        SecurityConfig.class,
        CommonWebMvcConfig.class,
        GlobalExceptionHandler.class,
        BulkUploadExceptionHandler.class,
        TimeProvider.class,
        SellerProductBulkControllerTest.FixedClockConfig.class
})
@DisplayName("판매자 상품 대량 등록 컨트롤러")
class SellerProductBulkControllerTest {

    private static final String BULK_PATH = "/api/v1/seller/products/bulk";
    private static final UUID SELLER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

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
    private BulkProductUploadProcessor bulkProductUploadProcessor;

    private static MockMultipartFile xlsx() {
        return new MockMultipartFile("file", "products.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {1, 2, 3});
    }

    /**
     * 토큰 없이 업로드하면 401이며 처리기를 호출하지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("미인증 업로드는 401, 처리하지 않는다")
    void unauthenticatedReturnsUnauthorized() throws Exception {
        mockMvc.perform(multipart(BULK_PATH).file(xlsx()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        then(bulkProductUploadProcessor).shouldHaveNoInteractions();
    }

    /**
     * SELLER가 아닌 권한으로 업로드하면 403이며 처리기를 호출하지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("비-SELLER 업로드는 403, 처리하지 않는다")
    void nonSellerReturnsForbidden() throws Exception {
        mockMvc.perform(multipart(BULK_PATH).file(xlsx())
                        .with(user(SELLER_ID.toString()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());

        then(bulkProductUploadProcessor).shouldHaveNoInteractions();
    }

    /**
     * SELLER 업로드는 인증 주체 id와 파일을 처리기에 넘기고, 검증 거절도 200 성공 봉투 안의 결과로 돌려준다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("SELLER 업로드는 인증 판매자 id로 처리하고, 결과(검증 거절 포함)를 200 성공 봉투로 돌려준다")
    void sellerUploadReturnsEnvelopedResult() throws Exception {
        given(bulkProductUploadProcessor.process(eq(SELLER_ID), any())).willReturn(new BulkProductUploadResponse(
                "REJECTED", List.of(),
                List.of(new UploadError("2.옵션", 5, "D", "P-001", "COMMON-001", "공백일 수 없습니다")), 1));

        mockMvc.perform(multipart(BULK_PATH).file(xlsx())
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.errors[0].sheet").value("2.옵션"))
                .andExpect(jsonPath("$.data.errors[0].row").value(5))
                .andExpect(jsonPath("$.data.errors[0].column").value("D"))
                .andExpect(jsonPath("$.data.totalErrorCount").value(1));

        then(bulkProductUploadProcessor).should().process(eq(SELLER_ID), any());
    }

    /**
     * 파일을 해석할 수 없으면(처리기가 거절) 400 PRODUCT-012 실패 봉투다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("해석할 수 없는 파일은 400 PRODUCT-012 실패 봉투로 돌려준다")
    void unreadableFileReturnsBadRequest() throws Exception {
        given(bulkProductUploadProcessor.process(eq(SELLER_ID), any()))
                .willThrow(new InvalidBulkUploadException("xlsx 파일을 읽을 수 없습니다"));

        mockMvc.perform(multipart(BULK_PATH).file(xlsx())
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-012"));
    }

    /**
     * 판매자별 한도 초과는 429 PRODUCT-013 실패 봉투이고, 다시 시도할 시간을 Retry-After(초)로 알린다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("한도 초과는 429 PRODUCT-013 실패 봉투 + Retry-After 헤더로 돌려준다")
    void rateLimitedReturnsTooManyRequestsWithRetryAfter() throws Exception {
        given(bulkProductUploadProcessor.process(eq(SELLER_ID), any()))
                .willThrow(new BulkUploadRateLimitedException(37, "대량 등록 건수 한도 초과: seller=" + SELLER_ID));

        mockMvc.perform(multipart(BULK_PATH).file(xlsx())
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "37"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.status").value(429))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-013"))
                .andExpect(jsonPath("$.error.message").value(ProductErrorCode.BULK_UPLOAD_RATE_LIMITED.getMessage()));
    }

    /**
     * 한도를 판정할 수 없으면(Redis 장애) 503 PRODUCT-014 실패 봉투이며, 내부 사유(판매자 id 등)는 응답에 싣지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("한도 판정 불가(Redis 장애)는 503 PRODUCT-014 실패 봉투로 돌려주고 Retry-After는 붙이지 않는다")
    void limiterUnavailableReturnsServiceUnavailable() throws Exception {
        given(bulkProductUploadProcessor.process(eq(SELLER_ID), any()))
                .willThrow(new BulkUploadUnavailableException("대량 등록 한도 판정 실패(Redis): seller=" + SELLER_ID,
                        new IllegalStateException("connection refused")));

        mockMvc.perform(multipart(BULK_PATH).file(xlsx())
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(503))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-014"))
                .andExpect(jsonPath("$.error.message").value(ProductErrorCode.BULK_UPLOAD_UNAVAILABLE.getMessage()));
    }

    /**
     * file 파트가 없으면 400이며 처리기를 호출하지 않는다.
     *
     * @throws Exception MockMvc 수행 실패 시
     */
    @Test
    @DisplayName("file 파트가 없으면 400, 처리하지 않는다")
    void missingFilePartReturnsBadRequest() throws Exception {
        mockMvc.perform(multipart(BULK_PATH)
                        .with(user(SELLER_ID.toString()).roles("SELLER")))
                .andExpect(status().isBadRequest());

        then(bulkProductUploadProcessor).shouldHaveNoInteractions();
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
