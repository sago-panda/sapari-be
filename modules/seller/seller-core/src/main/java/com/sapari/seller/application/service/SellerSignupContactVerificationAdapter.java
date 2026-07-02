package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.seller.command.SellerEmailVerificationConfirmCommand;
import com.sapari.seller.command.SellerEmailVerificationSendCommand;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.seller.view.SellerEmailVerificationConfirmResult;
import com.sapari.seller.view.SellerEmailVerificationSendResult;
import com.sapari.user.command.SignupEmailVerificationConfirmCommand;
import com.sapari.user.command.SignupEmailVerificationSendCommand;
import com.sapari.user.port.UserSignupEmailVerificationUseCase;
import com.sapari.user.view.SignupEmailVerificationConfirmResult;
import com.sapari.user.view.SignupEmailVerificationSendResult;

/**
 * seller 회원가입 이메일 인증 flow와 user 인증 정책 사이의 경계 adapter다.
 * seller endpoint에는 USER-* 내부 오류를 노출하지 않고 SELLER-* 오류 계약으로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class SellerSignupContactVerificationAdapter {

    private final UserSignupEmailVerificationUseCase userSignupEmailVerificationUseCase;

    /**
     * user가 소유한 회원가입 이메일 인증 발송 정책을 호출하고 seller 응답 모델로 변환한다.
     */
    public SellerEmailVerificationSendResult sendEmailVerification(SellerEmailVerificationSendCommand command) {
        try {
            SignupEmailVerificationSendResult result = userSignupEmailVerificationUseCase.sendSignupEmailVerification(
                    new SignupEmailVerificationSendCommand(command.email())
            );
            return new SellerEmailVerificationSendResult(
                    result.sent(),
                    result.expiresInSeconds(),
                    result.resendAvailableInSeconds()
            );
        } catch (BusinessException e) {
            throw mapUserEmailVerificationException(e);
        }
    }

    /**
     * 이메일 인증번호 검증 상태는 user가 소유하지만 seller endpoint 응답에는 SELLER-* 코드만 사용한다.
     */
    public SellerEmailVerificationConfirmResult confirmEmailVerification(SellerEmailVerificationConfirmCommand command) {
        try {
            SignupEmailVerificationConfirmResult result = userSignupEmailVerificationUseCase.confirmSignupEmailVerification(
                    new SignupEmailVerificationConfirmCommand(command.email(), command.code())
            );
            return new SellerEmailVerificationConfirmResult(
                    result.emailVerified(),
                    result.verifiedExpiresInSeconds()
            );
        } catch (BusinessException e) {
            throw mapUserEmailVerificationException(e);
        }
    }

    /**
     * 회원가입 저장 직전에 이메일 verified 상태를 1회 소비해 동일 인증 결과의 재사용을 막는다.
     */
    public void consumeEmailVerification(String email) {
        try {
            userSignupEmailVerificationUseCase.consumeSignupEmailVerification(email);
        } catch (BusinessException e) {
            throw mapUserEmailVerificationException(e);
        }
    }

    /**
     * user-api 뒤 구현체에서 발생한 회원가입 이메일 인증 오류를 seller API 오류 계약으로 변환한다.
     */
    private RuntimeException mapUserEmailVerificationException(BusinessException exception) {
        // seller-core는 user-core에 의존할 수 없으므로 ErrorCode의 공개 문자열 계약만 보고 변환한다.
        SellerErrorCode sellerErrorCode = mapUserEmailVerificationErrorCode(exception);
        return toSellerException(sellerErrorCode, exception);
    }

    private SellerException toSellerException(SellerErrorCode sellerErrorCode, BusinessException exception) {
        if (sellerErrorCode == null) {
            return new SellerException(SellerErrorCode.SIGNUP_VERIFICATION_UNAVAILABLE, exception);
        }

        return new SellerException(sellerErrorCode, exception);
    }

    private SellerErrorCode mapUserEmailVerificationErrorCode(BusinessException exception) {
        // seller-core는 user-core에 의존할 수 없으므로 ErrorCode의 공개 문자열 계약만 보고 변환한다.
        return switch (exception.getErrorCode().getCode()) {
            case "USER-108" -> SellerErrorCode.EMAIL_VERIFICATION_REQUIRED;
            case "USER-109" -> SellerErrorCode.EMAIL_VERIFICATION_CODE_NOT_FOUND;
            case "USER-110" -> SellerErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH;
            case "USER-111" -> SellerErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED;
            case "USER-112" -> SellerErrorCode.EMAIL_VERIFICATION_COOLDOWN;
            case "USER-106" -> SellerErrorCode.EMAIL_SEND_UNAVAILABLE;
            case "USER-113" -> SellerErrorCode.DUPLICATED_EMAIL;
            default -> null;
        };
    }
}
