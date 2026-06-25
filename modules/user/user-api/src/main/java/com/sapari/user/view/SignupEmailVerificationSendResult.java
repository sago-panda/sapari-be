package com.sapari.user.view;

/**
 * 회원가입 이메일 인증번호 발송 결과다.
 * TTL 값은 클라이언트 표시용이며 실제 제한은 Redis 상태가 기준이다.
 */
public record SignupEmailVerificationSendResult(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {
}
