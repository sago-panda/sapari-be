package com.sapari.customer.view;

/**
 * 구매자 회원가입 휴대폰 인증번호 발송 결과를 표현한다.
 * 만료 시간과 재요청 가능 시간은 클라이언트 안내용이며 서버 정책은 Redis TTL을 기준으로 판단한다.
 */
public record CustomerPhoneVerificationSendResult(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {
}
