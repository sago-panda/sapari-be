package com.sapari.seller.command;

/**
 * 판매자 회원가입 이메일 인증번호 확인 command다.
 */
public record SellerEmailVerificationConfirmCommand(String email, String code) {
}
