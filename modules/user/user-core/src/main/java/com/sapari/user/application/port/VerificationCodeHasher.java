package com.sapari.user.application.port;

/**
 * 회원가입 연락처와 인증번호를 Redis key/value에 저장하기 전 HMAC으로 변환한다.
 * 원문 전화번호, 이메일, 인증번호가 Redis에 남지 않도록 저장소 앞단에서 동일한 해시 정책을 제공한다.
 */
public interface VerificationCodeHasher {

    /**
     * 전화번호로 Redis key에 사용할 phoneHash를 만든다.
     */
    String hashPhoneNumber(String phoneNumber);

    /**
     * 이메일로 Redis key에 사용할 emailHash를 만든다.
     */
    String hashEmail(String email);

    /**
     * 전화번호와 인증번호로 Redis에 저장할 codeHash를 만든다.
     */
    String hashCode(String phoneNumber, String code);

    /**
     * 이메일과 인증번호로 Redis에 저장할 codeHash를 만든다.
     */
    String hashEmailCode(String email, String code);
}
