package com.sapari.customer.view;

/**
 * 구매자 회원가입 이메일 인증번호 발송 결과다.
 */
public record CustomerEmailVerificationSendResult(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {
}
