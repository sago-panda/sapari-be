package com.sapari.user.command;

/**
 * 회원가입 휴대폰 인증번호 발송 요청을 표현한다.
 */
public record SignupPhoneVerificationSendCommand(String phoneNumber) {
}
