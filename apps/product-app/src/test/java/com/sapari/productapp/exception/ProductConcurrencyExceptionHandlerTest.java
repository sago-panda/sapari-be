package com.sapari.productapp.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@DisplayName("ProductConcurrencyExceptionHandler")
class ProductConcurrencyExceptionHandlerTest {

    private static final Instant NOW = Instant.parse("2026-06-24T00:00:00Z");

    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    private final ProductConcurrencyExceptionHandler handler =
            new ProductConcurrencyExceptionHandler(timeProvider);

    @Test
    @DisplayName("in-flight 낙관적 락 충돌을 409 PRODUCT-011 봉투로 매핑한다(코드·메시지·상태·시각)")
    void optimisticLock_mapsTo409() {
        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("동시 수정 충돌"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().data()).isNull();
        assertThat(response.getBody().error().status()).isEqualTo(409);
        assertThat(response.getBody().error().code())
                .isEqualTo(ProductErrorCode.CONCURRENT_MODIFICATION.getCode());
        assertThat(response.getBody().error().message())
                .isEqualTo(ProductErrorCode.CONCURRENT_MODIFICATION.getMessage());
        assertThat(response.getBody().error().timestamp()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("행 잠금 경합(교착 감지·잠금 획득 실패)을 409 PRODUCT-011 봉투로 매핑한다")
    void lockContention_mapsTo409() {
        ResponseEntity<ResponseEnvelope<Void>> response =
                handler.handleLockContention(new CannotAcquireLockException("deadlock detected"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(response.getBody().error().code())
                .isEqualTo(ProductErrorCode.CONCURRENT_MODIFICATION.getCode());
        assertThat(response.getBody().error().timestamp()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("advice 체인에서 공통 catch-all(500)보다 우선 잡혀 409로 응답한다 (@Order 검증)")
    void inFlightConflict_winsOverCommonCatchAll() throws Exception {
        // 공통 핸들러를 먼저 등록해도, @Order(HIGHEST_PRECEDENCE)인 동시성 핸들러가 우선해야 한다(아니면 500).
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler(timeProvider), handler)
                .build();

        mockMvc.perform(get("/__test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.status").value(409))
                .andExpect(jsonPath("$.error.code").value("PRODUCT-011"));
        mockMvc.perform(get("/__test/deadlock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PRODUCT-011"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/__test/optimistic-lock")
        String throwOptimisticLock() {
            throw new OptimisticLockingFailureException("probe");
        }

        @GetMapping("/__test/deadlock")
        String throwDeadlock() {
            throw new CannotAcquireLockException("probe deadlock");
        }
    }
}
