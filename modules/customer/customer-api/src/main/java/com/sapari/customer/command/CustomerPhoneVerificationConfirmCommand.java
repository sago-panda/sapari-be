package com.sapari.customer.command;

/**
 * 구매자 회원가입 휴대폰 인증번호 확인 요청을 표현한다.
 * 인증번호 원문은 검증 시점에만 사용하고 저장소에는 해시된 값만 남긴다.
 */
public record CustomerPhoneVerificationConfirmCommand(String phoneNumber, String code) {
}
