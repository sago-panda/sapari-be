package com.sapari.user.application.port;

/**
 * SMS/이메일 인증에서 사용할 일회성 인증번호 원문을 생성한다.
 * 발송 수단과 분리해 인증번호 길이, zero-padding, 난수 정책을 한 곳에서 관리한다.
 */
public interface VerificationCodeGenerator {

    /**
     * 요청한 길이의 숫자 인증번호를 생성한다.
     *
     * @throws IllegalArgumentException 인증번호 길이가 0 이하인 경우
     */
    String generateNumericCode(int length);
}
