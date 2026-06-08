package com.sapari.common.response;

import java.time.Instant;

import com.sapari.common.core.exception.ErrorCode;

/**
 * 표준 에러 응답.
 *
 * {@code code}: 명시적으로 구분할 수 있는 에러 식별자(ex. MEMBER-001),
 * {@code requestId}: 서버 로그와 연결하기 위한 식별자
 */
public record ErrorResponse(
        int status,
        String code,
        String message,
        String requestId,
        Instant timestamp
) {
    public static ErrorResponse of(ErrorCode errorCode, String requestId, Instant timestamp) {
        return new ErrorResponse(
                errorCode.getStatus(),
                errorCode.getCode(),
                errorCode.getMessage(),
                requestId,
                timestamp
        );
    }
}
