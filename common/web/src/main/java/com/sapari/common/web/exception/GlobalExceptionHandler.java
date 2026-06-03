package com.sapari.common.web.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;

import jakarta.validation.ConstraintViolationException;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.common.core.exception.CommonErrorCode;
import com.sapari.common.core.exception.ErrorCode;
import com.sapari.common.web.response.ErrorResponse;
import com.sapari.global.time.TimeProvider;

/**
 * 전역 예외 처리. 모든 REST 앱(api/admin)이 com.sapari 컴포넌트 스캔으로 자동 등록한다.
 *
 * <p>보안 원칙:
 * <ul>
 *   <li>응답 메시지는 항상 errorCode 카탈로그 메시지 — 예외의 커스텀 메시지/내부 detail은 응답에 넣지 않는다.</li>
 *   <li>미처리 예외는 고정 문구로 응답하고, 실제 예외/스택은 서버 로그에만 남긴다.</li>
 * </ul>
 *
 * <p>참고: WebSocket(streaming) 메시지 예외는 이 advice로 잡히지 않는다 → 별도 @MessageExceptionHandler 필요.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String REQUEST_ID = "requestId";
    private static final String VALIDATION_FALLBACK_MESSAGE = "잘못된 요청입니다.";

    private final TimeProvider timeProvider;

    /** 모든 도메인 비즈니스 예외 (BusinessException 하위) */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus() >= 500) {
            // 5xx는 스택트레이스까지. exception.getMessage()(커스텀=roomId 등)는 로그에만.
            log.error("[{}] {}", errorCode.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("[{}] {}", errorCode.getCode(), exception.getMessage());
        }
        return toResponse(errorCode);
    }

    /** Bean Validation: @RequestBody DTO */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        return validationResponse(message);
    }

    /** Bean Validation: @RequestParam/@PathVariable (@Validated 컨트롤러) */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        return validationResponse(message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        String message = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        return validationResponse(message);
    }

    /** 잘못된 JSON 본문 / 경로·파라미터 타입 미스매치 → 400. 내부 detail은 응답에 노출하지 않음. */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
        log.warn("[{}] {}", CommonErrorCode.INVALID_INPUT.getCode(), exception.getMessage());
        return toResponse(CommonErrorCode.INVALID_INPUT);
    }

    /** 미처리 예외 fallback → 500. 실제 예외는 로그에만, 응답은 고정 문구. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return toResponse(CommonErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<ErrorResponse> validationResponse(String message) {
        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT;
        log.warn("[{}] validation: {}", errorCode.getCode(), message);
        return ResponseEntity.status(errorCode.getStatus())
                .body(new ErrorResponse(
                        errorCode.getStatus(), errorCode.getCode(), message, requestId(), timeProvider.now()));
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, requestId(), timeProvider.now()));
    }

    private String requestId() {
        return MDC.get(REQUEST_ID);
    }
}
