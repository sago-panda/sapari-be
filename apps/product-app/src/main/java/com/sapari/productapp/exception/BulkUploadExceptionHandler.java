package com.sapari.productapp.exception;

import com.sapari.common.response.ErrorResponse;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.global.time.TimeProvider;
import com.sapari.product.domain.exception.BulkUploadRateLimitedException;
import com.sapari.product.domain.exception.ProductErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 대량 등록 업로드 한도 초과 처리.
 *
 * <p>파일 크기: 서블릿 multipart 한도({@code config/MultipartConfig})를 넘으면 컨트롤러에 닿기 전에
 * {@link MaxUploadSizeExceededException}이 나는데, 공통 핸들러는 이를 일반 413으로 돌려준다. 한도 안쪽에서 처리기가 거절할 때와
 * 같은 400 PRODUCT-012(템플릿 형식·한도 확인)로 맞춰, 클라이언트가 어느 경로로 거절됐는지와 상관없이 한 가지 응답만 다루게 한다.
 *
 * <p>요청 빈도: 판매자별 한도 초과({@link BulkUploadRateLimitedException})는 429 PRODUCT-013에 {@code Retry-After}(초)를 붙인다 — 공통 핸들러는
 * 헤더를 실을 수 없다.
 *
 * <p>공통 핸들러보다 먼저 잡히게 {@link Order} 최우선으로 등록한다.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
@RequiredArgsConstructor
public class BulkUploadExceptionHandler {

    private static final String REQUEST_ID = "requestId";

    private final TimeProvider timeProvider;

    /**
     * multipart 한도 초과를 400 PRODUCT-012로 응답한다.
     *
     * @param exception 서블릿 multipart 파싱 중 발생한 한도 초과
     * @return 400 BAD_REQUEST 응답 봉투
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        ProductErrorCode errorCode = ProductErrorCode.INVALID_BULK_UPLOAD;
        log.warn("[{}] 업로드 크기 한도 초과(한도 {} bytes): {}",
                errorCode.getCode(), exception.getMaxUploadSize(), exception.getMessage());
        ErrorResponse error = ErrorResponse.of(errorCode, MDC.get(REQUEST_ID), timeProvider.now());
        return ResponseEntity.status(errorCode.getStatus()).body(ResponseEnvelope.fail(error));
    }

    /**
     * 판매자별 대량 등록 한도 초과를 429 PRODUCT-013으로 응답하고, 다시 시도할 때까지의 시간을 {@code Retry-After}(초)로 알린다.
     *
     * @param exception 한도 판정기가 던진 초과 예외
     * @return 429 TOO_MANY_REQUESTS 응답 봉투 + Retry-After 헤더
     */
    @ExceptionHandler(BulkUploadRateLimitedException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleRateLimited(BulkUploadRateLimitedException exception) {
        ProductErrorCode errorCode = exception.getErrorCode();
        log.warn("[{}] {} (Retry-After {}s)", errorCode.getCode(), exception.getMessage(), exception.getRetryAfterSeconds());
        ErrorResponse error = ErrorResponse.of(errorCode, MDC.get(REQUEST_ID), timeProvider.now());
        return ResponseEntity.status(errorCode.getStatus())
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(ResponseEnvelope.fail(error));
    }
}
