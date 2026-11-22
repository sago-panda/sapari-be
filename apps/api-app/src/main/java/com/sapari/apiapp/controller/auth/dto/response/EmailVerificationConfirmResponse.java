package com.sapari.apiapp.controller.auth.dto.response;

import com.sapari.customer.view.CustomerEmailVerificationConfirmResult;
import com.sapari.seller.view.SellerEmailVerificationConfirmResult;

/**
 * 회원가입 이메일 인증번호 확인 응답 DTO다.
 * emailVerified는 화면 상태 표시용이며, 최종 가입 허용은 가입 API가 Redis verified를 소비할 때 결정된다.
 */
public record EmailVerificationConfirmResponse(boolean emailVerified, long verifiedExpiresInSeconds) {

    /**
     * customer flow의 이메일 인증 결과를 공용 auth 응답 DTO로 변환한다.
     */
    public static EmailVerificationConfirmResponse from(CustomerEmailVerificationConfirmResult result) {
        return new EmailVerificationConfirmResponse(
                result.emailVerified(),
                result.verifiedExpiresInSeconds()
        );
    }

    /**
     * seller flow의 이메일 인증 결과를 공용 auth 응답 DTO로 변환한다.
     */
    public static EmailVerificationConfirmResponse from(SellerEmailVerificationConfirmResult result) {
        return new EmailVerificationConfirmResponse(
                result.emailVerified(),
                result.verifiedExpiresInSeconds()
        );
    }
}
