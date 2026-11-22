package com.sapari.seller.command;

/**
 * 판매자 회원가입 이메일 인증번호 발송 command다.
 */
public record SellerEmailVerificationSendCommand(String email) {
}
