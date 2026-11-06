package com.sapari.productapp.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.domain.exception.BulkUploadRateLimitedException;
import com.sapari.product.domain.exception.ProductErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@DisplayName("BulkUploadExceptionHandler")
class BulkUploadExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-11-04T00:00:00Z");
    private static final long LIMIT = 5L * 1024 * 1024;

    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    private final BulkUploadExceptionHandler handler = new BulkUploadExceptionHandler(timeProvider);

    /**
     * 한도 초과를 처리기 거절과 같은 400 PRODUCT-012 봉투로 매핑하는지(상태·코드·카탈로그 메시지·시각, 한도 값 미노출).
     */
    @Test
    @DisplayName("multipart 한도 초과를 400 PRODUCT-012 봉투로 매핑한다(코드·카탈로그 메시지·상태·시각)")
    void maxUploadSize_mapsTo400Product012() {
        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleMaxUploadSize(new MaxUploadSizeExceededException(LIMIT));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().status()).isEqualTo(400);
        assertThat(response.getBody().error().code()).isEqualTo(ProductErrorCode.INVALID_BULK_UPLOAD.getCode());
        assertThat(response.getBody().error().message())
                .isEqualTo(ProductErrorCode.INVALID_BULK_UPLOAD.getMessage())
                .doesNotContain(String.valueOf(LIMIT));
        assertThat(response.getBody().error().timestamp()).isEqualTo(NOW);
    }

    /**
     * 공통 핸들러(ResponseEntityExceptionHandler → 413 일반 코드)보다 먼저 잡히는지 advice 체인으로 확인한다.
     */
    @Test
    @DisplayName("advice 체인에서 공통 핸들러(413)보다 우선 잡혀 400 PRODUCT-012로 응답한다 (@Order 검증)")
    void maxUploadSize_winsOverCommonHandler() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider), handler)
                .build();

        mockMvc.perform(post("/__test/too-large"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(400))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-012"));
    }

    /**
     * 판매자별 한도 초과를 429 PRODUCT-013 봉투로 매핑하고, 예외가 든 대기 시간을 Retry-After 헤더로 옮기는지(디버그 메시지 미노출).
     */
    @Test
    @DisplayName("한도 초과를 429 PRODUCT-013 봉투 + Retry-After(초) 헤더로 매핑하고 디버그 메시지는 싣지 않는다")
    void rateLimited_mapsTo429WithRetryAfter() {
        String debug = "대량 등록 건수 한도(10건/10분) 초과: seller=00000000-0000-0000-0000-0000000000a1";

        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleRateLimited(new BulkUploadRateLimitedException(600, debug));

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().get(HttpHeaders.RETRY_AFTER)).containsExactly("600");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().status()).isEqualTo(429);
        assertThat(response.getBody().error().code()).isEqualTo(ProductErrorCode.BULK_UPLOAD_RATE_LIMITED.getCode());
        assertThat(response.getBody().error().message())
                .isEqualTo(ProductErrorCode.BULK_UPLOAD_RATE_LIMITED.getMessage())
                .doesNotContain("seller");
        assertThat(response.getBody().error().timestamp()).isEqualTo(NOW);
    }

    /**
     * 공통 핸들러(BusinessException → 헤더 없는 429)보다 먼저 잡혀 Retry-After가 실리는지 advice 체인으로 확인한다.
     */
    @Test
    @DisplayName("advice 체인에서 공통 BusinessException 핸들러보다 우선 잡혀 Retry-After가 실린다 (@Order 검증)")
    void rateLimited_winsOverCommonHandler() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider), handler)
                .build();

        mockMvc.perform(post("/__test/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-013"));
    }

    @RestController
    static class ProbeController {

        /**
         * 판매자별 한도 초과를 흉내 낸다.
         */
        @PostMapping("/__test/rate-limited")
        String throwRateLimited() {
            throw new BulkUploadRateLimitedException(10, "같은 판매자의 대량 등록을 처리 중");
        }

        /**
         * multipart 파싱 단계의 한도 초과를 흉내 낸다.
         */
        @PostMapping("/__test/too-large")
        String throwMaxUploadSize() {
            throw new MaxUploadSizeExceededException(LIMIT);
        }
    }
}
