package com.sapari.customer.view;

/**
 * 구매자 회원가입 이메일 인증번호 확인 결과다.
 */
public record CustomerEmailVerificationConfirmResult(boolean emailVerified, long verifiedExpiresInSeconds) {
}
