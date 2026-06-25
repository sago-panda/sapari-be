package com.sapari.user.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements ErrorCode {

    SIGNUP_PHONE_VERIFICATION_REQUIRED(400, "USER-101", "휴대폰 인증이 필요합니다."),
    SIGNUP_PHONE_VERIFICATION_CODE_NOT_FOUND(400, "USER-102", "인증번호가 만료되었거나 다시 요청이 필요합니다."),
    SIGNUP_PHONE_VERIFICATION_CODE_MISMATCH(400, "USER-103", "인증번호가 올바르지 않습니다."),
    SIGNUP_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED(400, "USER-104", "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    SIGNUP_PHONE_VERIFICATION_COOLDOWN(429, "USER-105", "인증번호 재요청은 잠시 후 가능합니다."),
    SIGNUP_VERIFICATION_SEND_UNAVAILABLE(503, "USER-106", "인증번호 발송이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    DUPLICATED_PHONE_NUMBER(409, "USER-107", "이미 사용 중인 전화번호입니다.");

    private final int status;
    private final String code;
    private final String message;
}
