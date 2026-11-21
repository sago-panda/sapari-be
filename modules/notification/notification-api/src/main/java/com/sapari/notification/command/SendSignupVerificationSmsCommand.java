package com.sapari.notification.command;

/**
 * 회원가입 인증 SMS 템플릿을 렌더링하기 위한 notification-api command다.
 * 호출 도메인은 수신 번호와 인증번호만 전달하고, 사용자에게 노출되는 문구 원문은 notification이 소유한다.
 */
public record SendSignupVerificationSmsCommand(String phoneNumber, String verificationCode) {

    /**
     * notification boundary에 빈 수신 번호나 빈 인증번호가 들어오면 provider 호출 전에 차단한다.
     */
    public SendSignupVerificationSmsCommand {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }
        if (verificationCode == null || verificationCode.isBlank()) {
            throw new IllegalArgumentException("verificationCode must not be blank");
        }
    }
}
