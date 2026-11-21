package com.sapari.seller.view;

/**
 * 판매자 회원가입 이메일 인증번호 확인 결과다.
 */
public record SellerEmailVerificationConfirmResult(boolean emailVerified, long verifiedExpiresInSeconds) {
}
