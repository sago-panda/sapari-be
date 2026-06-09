package com.sapari.common.web.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.MDC;

import jakarta.validation.ConstraintViolationException;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.common.core.exception.CommonErrorCode;
import com.sapari.common.core.exception.ErrorCode;
import com.sapari.common.response.ErrorResponse;
import com.sapari.common.response.ResponseEnvelope;
import com.sapari.global.time.TimeProvider;

/**
 * 전역 예외 처리. 모든 REST 앱(api/admin)이 com.sapari 컴포넌트 스캔으로 자동 등록한다.
 *
 * - 모든 에러는 {@link ResponseEnvelope#fail} 봉투로 감싸 성공 응답과 형식을 통일한다.
 *   HTTP status 는 그대로 4xx/5xx 를 유지하고, 봉투의 error 에 상세를 담는다.
 * - 응답 메시지는 errorCode 카탈로그 메시지(검증은 dev가 쓴 메시지). 예외의 내부 detail/스택은 응답에 안 넣는다.
 * - 미처리 예외는 고정 문구로 응답하고, 실제 예외/스택은 서버 로그에만 남긴다.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String REQUEST_ID = "requestId";
    private static final String VALIDATION_FALLBACK_MESSAGE = "잘못된 요청입니다.";

    private final TimeProvider timeProvider;

    /**
     * 모든 비즈니스 예외(BusinessException 하위: MemberException 등)를 잡는다.
     * when: 소셜 회원가입 시 signupSid 쿠키 만료·없음(MEMBER-006), 저장 중 이메일·전화번호 중복(MEMBER-009) 등
     * then: errorCode의 status/code/message로 응답. 5xx는 스택까지 error 로그, 4xx는 warn. 내부 detail은 로그에만.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        if (errorCode.getStatus() >= 500) {
            // 5xx는 스택트레이스까지. exception.getMessage()(커스텀=roomId 등)는 로그에만.
            log.error("[{}] {}", errorCode.getCode(), exception.getMessage(), exception);
        } else {
            log.warn("[{}] {}", errorCode.getCode(), exception.getMessage());
        }
        return ResponseEntity.status(errorCode.getStatus()).body(ResponseEnvelope.fail(body(errorCode)));
    }

    /**
     * 단일 파라미터(@RequestParam/@PathVariable)의 제약 위반(ConstraintViolationException)을 잡는다. @Validated 빈에서 발생.
     * when: 표준 MVC 경로 밖(예: @Service에 직접 @Validated)에서의 제약 위반 — 컨트롤러 경로는 보통 아래 5번으로 간다.
     * then: 첫 위반 메시지를 그대로 응답(COMMON-001).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleConstraintViolation(ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        log.warn("[{}] validation: {}", CommonErrorCode.INVALID_INPUT.getCode(), message);
        return ResponseEntity.badRequest().body(ResponseEnvelope.fail(validationBody(message)));
    }

    /**
     * 위 어디에도 안 걸린 미처리 예외 전부(Exception)를 잡는 최후의 그물 → 500.
     * when: 회원가입 중 Redis·DB 다운으로 인한 연결 오류, NPE 등 예기치 못한 런타임 오류
     * then: COMMON-004 고정 문구로만 응답. 실제 예외·스택은 서버 로그에만(내부 노출 금지).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseEnvelope<Void>> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return ResponseEntity.status(CommonErrorCode.INTERNAL_ERROR.getStatus())
                .body(ResponseEnvelope.fail(body(CommonErrorCode.INTERNAL_ERROR)));
    }

    /**
     * 요청 본문 DTO(@RequestBody @Valid)의 Bean Validation 실패(MethodArgumentNotValidException)를 잡는다.
     * when: POST /signup/social 본문의 email 형식 오류, gender가 MALE/FEMALE 아님, birthDate 누락(SocialSignupRequest)
     * then: 첫 위반 필드의 메시지를 응답(COMMON-001).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        log.warn("[{}] validation: {}", CommonErrorCode.INVALID_INPUT.getCode(), message);
        return super.handleExceptionInternal(
                ex, ResponseEnvelope.fail(validationBody(message)), headers, status, request);
    }

    /**
     * 메서드 파라미터(@RequestParam/@PathVariable 등) 제약 위반(HandlerMethodValidationException)을 잡는다.
     * when: GET /signup/check-email?email=형식오류(@Email), check-phone의 전화번호 패턴(@Pattern) 위반
     * then: 첫 위반 메시지를 응답(COMMON-001).
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .findFirst()
                .map(MessageSourceResolvable::getDefaultMessage)
                .orElse(VALIDATION_FALLBACK_MESSAGE);
        log.warn("[{}] validation: {}", CommonErrorCode.INVALID_INPUT.getCode(), message);
        return super.handleExceptionInternal(
                ex, ResponseEnvelope.fail(validationBody(message)), headers, status, request);
    }

    /**
     * ResponseEntityExceptionHandler가 다루는 표준 MVC 예외(404·405·415·깨진 JSON 등)가 모두 통과하는 공통 지점.
     * when: POST /signup/social 자리에 GET 호출 → 405, 본문 JSON이 깨지거나 birthDate가 잘못된 날짜 → 400
     * then: status는 실제값 그대로, code/message는 404·405만 전용·나머지는 4xx/5xx 일반값. fail 봉투로 감싼다.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // body 가 이미 ResponseEnvelope 면(위 검증 핸들러들이 감싸 호출) 그대로, 아니면 표준 에러를 fail 봉투로 감싼다.
        Object envelope = (body instanceof ResponseEnvelope<?>)
                ? body
                : wrapStandardError(ex, statusCode);
        return super.handleExceptionInternal(ex, envelope, headers, statusCode, request);
    }

    private ResponseEnvelope<Void> wrapStandardError(Exception ex, HttpStatusCode statusCode) {
        ErrorResponse error = standardErrorBody(statusCode);
        log.warn("[{}] {} {}", error.code(), statusCode.value(), ex.getClass().getSimpleName());
        return ResponseEnvelope.fail(error);
    }

    /**
     * 표준 MVC 예외 → ErrorResponse.
     * status 필드는 실제 statusCode를 그대로 쓴다(415·406 등에서 body.status가 매핑 코드의 status로
     * 잘못 찍히지 않도록). code/message는 의미 있는 것(404·405)만 전용 코드, 나머지는 4xx/5xx 일반값.
     * 모든 HTTP status마다 CommonErrorCode를 만들지 않는다 — code는 coarse 분류, status가 정밀 식별.
     */
    private ErrorResponse standardErrorBody(HttpStatusCode statusCode) {
        CommonErrorCode mapped = switch (statusCode.value()) {
            case 404 -> CommonErrorCode.NOT_FOUND;
            case 405 -> CommonErrorCode.METHOD_NOT_ALLOWED;
            default -> statusCode.is4xxClientError()
                    ? CommonErrorCode.INVALID_INPUT
                    : CommonErrorCode.INTERNAL_ERROR;
        };
        return new ErrorResponse(
                statusCode.value(), mapped.getCode(), mapped.getMessage(), requestId(), timeProvider.now());
    }

    private ErrorResponse body(ErrorCode errorCode) {
        return ErrorResponse.of(errorCode, requestId(), timeProvider.now());
    }

    private ErrorResponse validationBody(String message) {
        ErrorCode errorCode = CommonErrorCode.INVALID_INPUT;
        return new ErrorResponse(
                errorCode.getStatus(), errorCode.getCode(), message, requestId(), timeProvider.now());
    }

    private String requestId() {
        return MDC.get(REQUEST_ID);
    }
}
