package com.sapari.common.core.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 특정 도메인에 속하지 않는 공통 에러 코드.
 *
 * 입력 검증 실패, 미처리 예외(fallback) 등
 * GlobalExceptionHandler가 직접 만드는 응답에 사용한다.
 * 도메인 예외는 각 도메인의 ErrorCode를 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    INVALID_INPUT(400, "COMMON-001", "잘못된 요청입니다."),
    UNAUTHORIZED(401, "COMMON-002", "인증이 필요합니다."),
    FORBIDDEN(403, "COMMON-003", "접근 권한이 없습니다."),
    INTERNAL_ERROR(500, "COMMON-004", "서버 오류가 발생했습니다.");

    private final int status;
    private final String code;
    private final String message;
}
