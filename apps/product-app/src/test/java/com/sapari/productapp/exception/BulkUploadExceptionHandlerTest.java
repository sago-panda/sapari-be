package com.sapari.productapp.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sapari.common.response.ResponseEnvelope;
import com.sapari.common.web.exception.GlobalExceptionHandler;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.domain.exception.ProductErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @RestController
    static class ProbeController {

        /**
         * multipart 파싱 단계의 한도 초과를 흉내 낸다.
         */
        @PostMapping("/__test/too-large")
        String throwMaxUploadSize() {
            throw new MaxUploadSizeExceededException(LIMIT);
        }
    }
}
