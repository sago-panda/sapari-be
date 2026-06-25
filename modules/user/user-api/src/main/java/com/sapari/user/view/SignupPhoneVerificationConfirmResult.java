package com.sapari.user.view;

/**
 * 회원가입 휴대폰 인증번호 확인 결과를 표현한다.
 */
public record SignupPhoneVerificationConfirmResult(boolean phoneNumberVerified, long verifiedExpiresInSeconds) {
}
