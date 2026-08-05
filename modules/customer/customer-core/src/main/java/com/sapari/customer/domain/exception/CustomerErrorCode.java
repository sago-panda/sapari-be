package com.sapari.customer.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum CustomerErrorCode implements ErrorCode {

    INVALID_REFRESH_TOKEN(401, "CUSTOMER-001", "Refresh Token이 유효하지 않습니다."),
    INVALID_ACCESS_TOKEN(401, "CUSTOMER-002", "Access Token이 유효하지 않습니다."),
    USER_NOT_FOUND(404, "CUSTOMER-003", "사용자를 찾을 수 없습니다."),
    DUPLICATED_PHONE_NUMBER(409, "CUSTOMER-004", "이미 사용 중인 전화번호입니다."),
    DUPLICATED_EMAIL(409, "CUSTOMER-005", "이미 사용 중인 이메일입니다."),
    INVALID_SIGNUP_SESSION(400, "CUSTOMER-006", "소셜 고객 가입 세션이 유효하지 않습니다."),
    INVALID_LOGIN_CODE(400, "CUSTOMER-007", "임시 로그인 코드가 유효하지 않습니다."),
    INVALID_SOCIAL_INFO(400, "CUSTOMER-008", "소셜 인증 정보를 읽을 수 없습니다."),
    DUPLICATED_SIGNUP_INFO(409, "CUSTOMER-009", "이미 사용 중인 고객 정보입니다."),
    INVALID_OAUTH_PROVIDER(400, "CUSTOMER-010", "지원하지 않는 OAuth provider입니다."),
    DUPLICATED_NICKNAME(409, "CUSTOMER-011", "이미 사용 중인 닉네임입니다."),
    NICKNAME_CHANGE_RESTRICTED(409, "CUSTOMER-012", "닉네임은 30일마다 변경할 수 있습니다."),
    PHONE_VERIFICATION_REQUIRED(400, "CUSTOMER-013", "휴대폰 인증이 필요합니다."),
    PHONE_VERIFICATION_CODE_NOT_FOUND(400, "CUSTOMER-014", "인증번호가 만료되었거나 다시 요청이 필요합니다."),
    PHONE_VERIFICATION_CODE_MISMATCH(400, "CUSTOMER-015", "인증번호가 올바르지 않습니다."),
    PHONE_VERIFICATION_ATTEMPTS_EXCEEDED(400, "CUSTOMER-016", "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    PHONE_VERIFICATION_COOLDOWN(429, "CUSTOMER-017", "인증번호 재요청은 잠시 후 가능합니다."),
    SMS_SEND_UNAVAILABLE(503, "CUSTOMER-018", "인증번호 발송이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    EMAIL_VERIFICATION_REQUIRED(400, "CUSTOMER-019", "이메일 인증이 필요합니다."),
    EMAIL_VERIFICATION_CODE_NOT_FOUND(400, "CUSTOMER-020", "인증번호가 만료되었거나 다시 요청이 필요합니다."),
    EMAIL_VERIFICATION_CODE_MISMATCH(400, "CUSTOMER-021", "인증번호가 올바르지 않습니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(400, "CUSTOMER-022", "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    EMAIL_VERIFICATION_COOLDOWN(429, "CUSTOMER-023", "인증번호 재요청은 잠시 후 가능합니다."),
    EMAIL_SEND_UNAVAILABLE(503, "CUSTOMER-024", "인증번호 발송이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    SIGNUP_VERIFICATION_UNAVAILABLE(503, "CUSTOMER-025", "회원가입 인증 처리가 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    INVALID_PROFILE_IMAGE_CHOICE(400, "CUSTOMER-026", "프로필 이미지 선택이 올바르지 않습니다."),
    PROFILE_IMAGE_STORAGE_UNAVAILABLE(503, "CUSTOMER-027", "프로필 이미지 저장이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    SOCIAL_PROFILE_IMAGE_IMPORT_FAILED(502, "CUSTOMER-028", "소셜 프로필 이미지를 가져올 수 없습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String code;
    private final String message;
}
