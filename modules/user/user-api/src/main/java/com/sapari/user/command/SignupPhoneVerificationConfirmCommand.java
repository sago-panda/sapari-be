package com.sapari.user.command;

/**
 * 회원가입 휴대폰 인증번호 확인 요청을 표현한다.
 */
public record SignupPhoneVerificationConfirmCommand(String phoneNumber, String code) {
}
