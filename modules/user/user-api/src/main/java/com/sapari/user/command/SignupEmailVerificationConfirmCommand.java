package com.sapari.user.command;

/**
 * 회원가입 이메일 인증번호 확인 command다.
 * 사용자가 입력한 code는 원문 저장 없이 email과 함께 HMAC으로 비교된다.
 */
public record SignupEmailVerificationConfirmCommand(String email, String code) {
}
