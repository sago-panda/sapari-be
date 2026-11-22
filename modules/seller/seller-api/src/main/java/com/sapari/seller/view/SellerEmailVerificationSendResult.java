package com.sapari.seller.view;

/**
 * 판매자 회원가입 이메일 인증번호 발송 결과다.
 */
public record SellerEmailVerificationSendResult(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {
}
