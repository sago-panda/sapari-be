package com.sapari.customer.command;

/**
 * 구매자 회원가입 휴대폰 인증번호 발송 요청을 표현한다.
 * 전화번호 형식 검증은 controller DTO가 수행하고, 구현 서비스는 인증 상태 발급 정책을 처리한다.
 */
public record CustomerPhoneVerificationSendCommand(String phoneNumber) {
}
