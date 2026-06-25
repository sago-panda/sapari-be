package com.sapari.apiapp.controller.auth.dto.response;

import com.sapari.customer.view.CustomerEmailVerificationSendResult;
import com.sapari.seller.view.SellerEmailVerificationSendResult;

/**
 * 회원가입 이메일 인증번호 발송 응답 DTO다.
 * TTL 값은 클라이언트 타이머 표시용이며 실제 제한은 서버 Redis 상태로 판단한다.
 */
public record EmailVerificationSendResponse(boolean sent, long expiresInSeconds, long resendAvailableInSeconds) {

    /**
     * customer flow의 이메일 인증 발송 결과를 공용 auth 응답 DTO로 변환한다.
     */
    public static EmailVerificationSendResponse from(CustomerEmailVerificationSendResult result) {
        return new EmailVerificationSendResponse(
                result.sent(),
                result.expiresInSeconds(),
                result.resendAvailableInSeconds()
        );
    }

    /**
     * seller flow의 이메일 인증 발송 결과를 공용 auth 응답 DTO로 변환한다.
     */
    public static EmailVerificationSendResponse from(SellerEmailVerificationSendResult result) {
        return new EmailVerificationSendResponse(
                result.sent(),
                result.expiresInSeconds(),
                result.resendAvailableInSeconds()
        );
    }
}
