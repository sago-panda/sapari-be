package com.sapari.user.view;

/**
 * 회원가입 휴대폰 인증번호 발송 결과를 표현한다.
 */
public record SignupPhoneVerificationSendResult(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {
}
