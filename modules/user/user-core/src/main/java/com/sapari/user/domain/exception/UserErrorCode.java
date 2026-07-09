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
    DUPLICATED_PHONE_NUMBER(409, "USER-107", "이미 사용 중인 전화번호입니다."),
    SIGNUP_EMAIL_VERIFICATION_REQUIRED(400, "USER-108", "이메일 인증이 필요합니다."),
    SIGNUP_EMAIL_VERIFICATION_CODE_NOT_FOUND(400, "USER-109", "인증번호가 만료되었거나 다시 요청이 필요합니다."),
    SIGNUP_EMAIL_VERIFICATION_CODE_MISMATCH(400, "USER-110", "인증번호가 올바르지 않습니다."),
    SIGNUP_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(400, "USER-111", "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    SIGNUP_EMAIL_VERIFICATION_COOLDOWN(429, "USER-112", "인증번호 재요청은 잠시 후 가능합니다."),
    DUPLICATED_EMAIL(409, "USER-113", "이미 사용 중인 이메일입니다."),
    PROFILE_IMAGE_REQUIRED(400, "USER-114", "프로필 이미지 파일은 필수입니다."),
    PROFILE_IMAGE_TOO_LARGE(400, "USER-115", "프로필 이미지 파일 크기가 허용 범위를 초과했습니다."),
    PROFILE_IMAGE_UNSUPPORTED_TYPE(400, "USER-116", "지원하지 않는 프로필 이미지 형식입니다."),
    PROFILE_IMAGE_INVALID_CONTENT(400, "USER-117", "프로필 이미지 파일 내용이 올바르지 않습니다.");

    private final int status;
    private final String code;
    private final String message;
}
