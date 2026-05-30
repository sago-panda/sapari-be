package com.sapari.seller.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode {

    DUPLICATED_SIGNUP_INFO(409, "SELLER-001", "이미 사용 중인 판매자 회원 정보입니다."),
    INVALID_LOGIN_CREDENTIALS(401, "SELLER-002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_REFRESH_TOKEN(401, "SELLER-003", "Refresh Token이 유효하지 않습니다."),
    INVALID_ACCESS_TOKEN(401, "SELLER-004", "Access Token이 유효하지 않습니다."),
    USER_NOT_FOUND(404, "SELLER-005", "사용자를 찾을 수 없습니다."),
    DUPLICATED_NICKNAME(409, "SELLER-006", "이미 사용 중인 닉네임입니다."),
    DUPLICATED_PHONE_NUMBER(409, "SELLER-007", "이미 사용 중인 전화번호입니다."),
    DUPLICATED_EMAIL(409, "SELLER-008", "이미 사용 중인 이메일입니다."),
    NICKNAME_CHANGE_RESTRICTED(409, "SELLER-009", "닉네임은 30일마다 변경할 수 있습니다.");

    private final int status;
    private final String code;
    private final String message;
}
