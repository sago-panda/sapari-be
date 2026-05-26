package com.sapari.member.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode {

    INVALID_REFRESH_TOKEN(401, "MEMBER-001", "Refresh Token이 유효하지 않습니다."),
    INVALID_ACCESS_TOKEN(401, "MEMBER-002", "Access Token이 유효하지 않습니다."),
    USER_NOT_FOUND(404, "MEMBER-003", "사용자를 찾을 수 없습니다."),
    DUPLICATED_PHONE_NUMBER(409, "MEMBER-004", "이미 사용 중인 전화번호입니다."),
    DUPLICATED_EMAIL(409, "MEMBER-005", "이미 사용 중인 이메일입니다."),
    INVALID_SIGNUP_SESSION(400, "MEMBER-006", "소셜 회원가입 세션이 유효하지 않습니다."),
    INVALID_LOGIN_CODE(400, "MEMBER-007", "임시 로그인 코드가 유효하지 않습니다."),
    INVALID_SOCIAL_INFO(400, "MEMBER-008", "소셜 인증 정보를 읽을 수 없습니다."),
    DUPLICATED_SIGNUP_INFO(409, "MEMBER-009", "이미 사용 중인 회원 정보입니다."),
    INVALID_OAUTH_PROVIDER(400, "MEMBER-010", "지원하지 않는 OAuth provider입니다.");

    private final int status;
    private final String code;
    private final String message;
}
