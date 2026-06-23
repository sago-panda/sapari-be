package com.sapari.apiapp.controller.auth.dto.response;

import com.sapari.customer.view.CustomerPhoneVerificationSendResult;

/**
 * 구매자 회원가입 휴대폰 인증번호 발송 응답 DTO다.
 * TTL 값은 클라이언트 타이머 표시용이며 실제 제한은 서버 Redis 상태로 판단한다.
 */
public record PhoneVerificationSendResponse(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {

    /**
     * usecase 발송 결과를 API 응답으로 변환한다.
     */
    public static PhoneVerificationSendResponse from(CustomerPhoneVerificationSendResult result) {
        return new PhoneVerificationSendResponse(
                result.sent(),
                result.expiresInSeconds(),
                result.resendAvailableInSeconds()
        );
    }
}
