package com.sapari.customer.view;

/**
 * 구매자 회원가입 휴대폰 인증번호 확인 결과를 표현한다.
 * 인증 완료 TTL은 회원가입 API가 소비할 수 있는 서버 측 verified 상태의 남은 시간을 의미한다.
 */
public record CustomerPhoneVerificationConfirmResult(boolean phoneNumberVerified, long verifiedExpiresInSeconds) {
}
