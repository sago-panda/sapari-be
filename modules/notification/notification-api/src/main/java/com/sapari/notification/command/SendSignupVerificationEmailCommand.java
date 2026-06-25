package com.sapari.notification.command;

/**
 * 회원가입 이메일 인증번호 발송 요청이다.
 * 인증번호 생성과 검증 정책은 user가 소유하고, 메일 제목/본문 렌더링은 notification이 소유한다.
 */
public record SendSignupVerificationEmailCommand(String email, String verificationCode) {

    /**
     * notification boundary에 빈 수신자나 빈 인증번호가 들어오면 provider 호출 전에 차단한다.
     */
    public SendSignupVerificationEmailCommand {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (verificationCode == null || verificationCode.isBlank()) {
            throw new IllegalArgumentException("verificationCode must not be blank");
        }
    }
}
