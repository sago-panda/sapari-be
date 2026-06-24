package com.sapari.customer.application.port;

/**
 * 구매자 휴대폰 인증 문자를 발송하는 application port다.
 * SOLAPI 같은 외부 provider 세부 구현을 application service에서 분리한다.
 */
public interface SmsSender {

    /**
     * 지정한 전화번호로 인증번호 문자를 발송한다.
     */
    SmsSendResult sendVerificationCode(String phoneNumber, String code);
}
