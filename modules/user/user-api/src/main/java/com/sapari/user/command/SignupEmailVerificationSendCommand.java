package com.sapari.user.command;

/**
 * 회원가입 이메일 인증번호 발송 command다.
 * 이메일 중복 차단, 쿨다운, 발송 성공 후 저장 정책은 user 구현체가 서버 기준으로 처리한다.
 */
public record SignupEmailVerificationSendCommand(String email) {
}
