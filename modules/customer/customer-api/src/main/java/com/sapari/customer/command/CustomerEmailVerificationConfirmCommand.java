package com.sapari.customer.command;

/**
 * 구매자 회원가입 이메일 인증번호 확인 command다.
 */
public record CustomerEmailVerificationConfirmCommand(String email, String code) {
}
