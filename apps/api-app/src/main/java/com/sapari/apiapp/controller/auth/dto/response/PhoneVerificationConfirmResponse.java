package com.sapari.apiapp.controller.auth.dto.response;

import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;

/**
 * 구매자 회원가입 휴대폰 인증번호 확인 응답 DTO다.
 * 인증 완료 TTL은 이후 회원가입 API가 소비할 수 있는 서버 verified 상태의 남은 시간이다.
 */
public record PhoneVerificationConfirmResponse(boolean phoneNumberVerified, long verifiedExpiresInSeconds) {

    /**
     * usecase 확인 결과를 API 응답으로 변환한다.
     */
    public static PhoneVerificationConfirmResponse from(CustomerPhoneVerificationConfirmResult result) {
        return new PhoneVerificationConfirmResponse(
                result.phoneNumberVerified(),
                result.verifiedExpiresInSeconds()
        );
    }
}
