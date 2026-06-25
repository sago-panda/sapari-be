package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.user.port.UserSignupPhoneVerificationUseCase;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;

/**
 * customer 회원가입 연락처 인증 flow와 user 인증 정책 사이의 경계 adapter다.
 * 현재는 휴대폰 인증만 위임하며, customer endpoint에는 USER-* 내부 오류를 노출하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class CustomerSignupContactVerificationAdapter {

    private final UserSignupPhoneVerificationUseCase userSignupPhoneVerificationUseCase;

    /**
     * user가 소유한 회원가입 휴대폰 인증 발송 정책을 호출하고 customer 응답 모델로 변환한다.
     */
    public CustomerPhoneVerificationSendResult sendPhoneVerification(CustomerPhoneVerificationSendCommand command) {
        try {
            SignupPhoneVerificationSendResult result = userSignupPhoneVerificationUseCase.sendSignupPhoneVerification(
                    new SignupPhoneVerificationSendCommand(command.phoneNumber())
            );
            return new CustomerPhoneVerificationSendResult(
                    result.sent(),
                    result.expiresInSeconds(),
                    result.resendAvailableInSeconds()
            );
        } catch (BusinessException e) {
            throw mapUserPhoneVerificationException(e);
        }
    }

    /**
     * 인증번호 검증 상태는 user가 소유하지만 customer endpoint 응답에는 CUSTOMER-* 코드만 사용한다.
     */
    public CustomerPhoneVerificationConfirmResult confirmPhoneVerification(CustomerPhoneVerificationConfirmCommand command) {
        try {
            SignupPhoneVerificationConfirmResult result = userSignupPhoneVerificationUseCase.confirmSignupPhoneVerification(
                    new SignupPhoneVerificationConfirmCommand(command.phoneNumber(), command.code())
            );
            return new CustomerPhoneVerificationConfirmResult(
                    result.phoneNumberVerified(),
                    result.verifiedExpiresInSeconds()
            );
        } catch (BusinessException e) {
            throw mapUserPhoneVerificationException(e);
        }
    }

    /**
     * 회원가입 저장 직전에 verified 상태를 1회 소비해 동일 인증 결과의 재사용을 막는다.
     */
    public void consumePhoneVerification(String phoneNumber) {
        try {
            userSignupPhoneVerificationUseCase.consumeSignupPhoneVerification(phoneNumber);
        } catch (BusinessException e) {
            throw mapUserPhoneVerificationException(e);
        }
    }

    /**
     * user-api 뒤 구현체에서 발생한 회원가입 휴대폰 인증 오류를 customer API 오류 계약으로 변환한다.
     */
    private RuntimeException mapUserPhoneVerificationException(BusinessException exception) {
        // customer-core는 user-core에 의존할 수 없으므로 ErrorCode의 공개 문자열 계약만 보고 변환한다.
        CustomerErrorCode customerErrorCode = switch (exception.getErrorCode().getCode()) {
            case "USER-101" -> CustomerErrorCode.PHONE_VERIFICATION_REQUIRED;
            case "USER-102" -> CustomerErrorCode.PHONE_VERIFICATION_CODE_NOT_FOUND;
            case "USER-103" -> CustomerErrorCode.PHONE_VERIFICATION_CODE_MISMATCH;
            case "USER-104" -> CustomerErrorCode.PHONE_VERIFICATION_ATTEMPTS_EXCEEDED;
            case "USER-105" -> CustomerErrorCode.PHONE_VERIFICATION_COOLDOWN;
            case "USER-106" -> CustomerErrorCode.SMS_SEND_UNAVAILABLE;
            case "USER-107" -> CustomerErrorCode.DUPLICATED_PHONE_NUMBER;
            default -> null;
        };

        if (customerErrorCode == null) {
            return exception;
        }

        return new CustomerException(customerErrorCode, exception);
    }
}
