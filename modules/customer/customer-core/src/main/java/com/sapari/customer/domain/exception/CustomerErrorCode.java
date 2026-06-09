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
    NICKNAME_CHANGE_RESTRICTED(409, "CUSTOMER-012", "닉네임은 30일마다 변경할 수 있습니다.");

    private final int status;
    private final String code;
    private final String message;
}
