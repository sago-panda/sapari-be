package com.sapari.notification.command;

/**
 * 회원가입 인증 SMS 템플릿을 렌더링하기 위한 notification-api command다.
 * 호출 도메인은 수신 번호와 인증번호만 전달하고, 사용자에게 노출되는 문구 원문은 notification이 소유한다.
 */
public record SendSignupVerificationSmsCommand(String phoneNumber, String verificationCode) {
}
