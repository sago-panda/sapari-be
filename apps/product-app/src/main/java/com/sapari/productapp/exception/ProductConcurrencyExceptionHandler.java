package com.sapari.productapp.exception;

import com.sapari.common.response.ErrorResponse;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.domain.exception.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 상품 앱 전용 예외 처리. in-flight 낙관적 락 충돌({@link OptimisticLockingFailureException}, 커밋 시 발생)을 409로 매핑한다.
 * 공통 web에 spring-tx를 들이지 않으려 앱 레이어에 두고, 공통 catch-all(500)보다 먼저 잡히게 {@link Order} 최우선으로 등록한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class ProductConcurrencyExceptionHandler {

    private static final String REQUEST_ID = "requestId";

    private final TimeProvider timeProvider;

    /**
     * 동시 수정으로 인한 낙관적 락 충돌을 409로 응답한다(클라이언트 재시도 유도).
     *
     * @param exception 영속 계층에서 전파된 낙관적 락 충돌
     * @return 409 CONFLICT 응답 봉투
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleOptimisticLock(OptimisticLockingFailureException exception) {
        ProductErrorCode errorCode = ProductErrorCode.CONCURRENT_MODIFICATION;
        log.warn("[{}] 동시 수정 충돌(in-flight): {}", errorCode.getCode(), exception.getMessage());
        ErrorResponse error = ErrorResponse.of(errorCode, MDC.get(REQUEST_ID), timeProvider.now());
        return ResponseEntity.status(errorCode.getStatus()).body(ResponseEnvelope.fail(error));
    }
}
