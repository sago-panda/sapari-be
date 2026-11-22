package com.sapari.seller.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import com.sapari.common.core.exception.ErrorCode;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode implements ErrorCode {

    DUPLICATED_SIGNUP_INFO(409, "SELLER-001", "이미 사용 중인 판매자 회원 정보입니다."),
    INVALID_LOGIN_CREDENTIALS(401, "SELLER-002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(401, "SELLER-003", "Refresh Token이 유효하지 않습니다."),
    INVALID_ACCESS_TOKEN(401, "SELLER-004", "Access Token이 유효하지 않습니다."),
    USER_NOT_FOUND(404, "SELLER-005", "사용자를 찾을 수 없습니다."),
    DUPLICATED_NICKNAME(409, "SELLER-006", "이미 사용 중인 닉네임입니다."),
    DUPLICATED_PHONE_NUMBER(409, "SELLER-007", "이미 사용 중인 전화번호입니다."),
    DUPLICATED_EMAIL(409, "SELLER-008", "이미 사용 중인 이메일입니다."),
    NICKNAME_CHANGE_RESTRICTED(409, "SELLER-009", "닉네임은 30일마다 변경할 수 있습니다."),
    DUPLICATED_BUSINESS_NUMBER(409, "SELLER-010", "이미 등록된 사업자번호입니다."),
    INVALID_BUSINESS_TYPE(400, "SELLER-011", "사업자 유형이 올바르지 않습니다."),
    DUPLICATED_STORE_NAME(409, "SELLER-012", "이미 사용 중인 상호명입니다."),
    INVALID_BUSINESS_REGISTRATION(400, "SELLER-013", "가입 가능한 사업자등록번호가 아닙니다."),
    BUSINESS_REGISTRATION_CHECK_UNAVAILABLE(503, "SELLER-014", "사업자등록번호 확인이 지연되고 있습니다."),
    SELLER_PROFILE_NOT_FOUND(404, "SELLER-015", "판매자 프로필을 찾을 수 없습니다."),
    PASSWORD_CONFIRM_MISMATCH(400, "SELLER-016", "비밀번호와 비밀번호 확인이 일치하지 않습니다."),
    EMAIL_VERIFICATION_REQUIRED(400, "SELLER-017", "이메일 인증이 필요합니다."),
    EMAIL_VERIFICATION_CODE_NOT_FOUND(400, "SELLER-018", "인증번호가 만료되었거나 다시 요청이 필요합니다."),
    EMAIL_VERIFICATION_CODE_MISMATCH(400, "SELLER-019", "인증번호가 올바르지 않습니다."),
    EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED(400, "SELLER-020", "인증번호 입력 횟수를 초과했습니다. 다시 요청해 주세요."),
    EMAIL_VERIFICATION_COOLDOWN(429, "SELLER-021", "인증번호 재요청은 잠시 후 가능합니다."),
    EMAIL_SEND_UNAVAILABLE(503, "SELLER-022", "인증번호 발송이 지연되고 있습니다. 잠시 후 다시 시도해 주세요."),
    SIGNUP_VERIFICATION_UNAVAILABLE(503, "SELLER-023", "회원가입 인증 처리가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.");

    private final int status;
    private final String code;
    private final String message;
}
