package com.sapari.user.command;

/**
 * 회원가입 저장 직전에 휴대폰과 이메일 인증 완료 상태를 함께 소비하는 command다.
 */
public record SignupContactVerificationConsumeCommand(
        String phoneNumber,
        String email
) {
}
