package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.common.core.exception.BusinessException;
import com.sapari.customer.command.CustomerEmailVerificationConfirmCommand;
import com.sapari.customer.command.CustomerEmailVerificationSendCommand;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.view.CustomerEmailVerificationConfirmResult;
import com.sapari.customer.view.CustomerEmailVerificationSendResult;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;
import com.sapari.user.command.SignupContactVerificationConsumeCommand;
import com.sapari.user.command.SignupEmailVerificationConfirmCommand;
import com.sapari.user.command.SignupEmailVerificationSendCommand;
import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.user.port.UserSignupContactVerificationUseCase;
import com.sapari.user.port.UserSignupEmailVerificationUseCase;
import com.sapari.user.port.UserSignupPhoneVerificationUseCase;
import com.sapari.user.view.SignupEmailVerificationConfirmResult;
import com.sapari.user.view.SignupEmailVerificationSendResult;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;

/**
 * customer 회원가입 연락처 인증 flow와 user 인증 정책 사이의 경계 adapter다.
 * customer endpoint에는 USER-* 내부 오류를 노출하지 않고 CUSTOMER-* 오류 계약으로 변환한다.
 */
@Component
@RequiredArgsConstructor
public class CustomerSignupContactVerificationAdapter {

    private final UserSignupPhoneVerificationUseCase userSignupPhoneVerificationUseCase;
    private final UserSignupEmailVerificationUseCase userSignupEmailVerificationUseCase;
    private final UserSignupContactVerificationUseCase userSignupContactVerificationUseCase;

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
     * 회원가입 저장 직전에 휴대폰 verified 상태를 1회 소비해 동일 인증 결과의 재사용을 막는다.
     */
    public void consumePhoneVerification(String phoneNumber) {
        try {
            userSignupPhoneVerificationUseCase.consumeSignupPhoneVerification(phoneNumber);
        } catch (BusinessException e) {
            throw mapUserPhoneVerificationException(e);
        }
    }


    /**
     * user가 소유한 회원가입 이메일 인증 발송 정책을 호출하고 customer 응답 모델로 변환한다.
     */
    public CustomerEmailVerificationSendResult sendEmailVerification(CustomerEmailVerificationSendCommand command) {
        try {
            SignupEmailVerificationSendResult result = userSignupEmailVerificationUseCase.sendSignupEmailVerification(
                    new SignupEmailVerificationSendCommand(command.email())
            );
            return new CustomerEmailVerificationSendResult(
                    result.sent(),
                    result.expiresInSeconds(),
                    result.resendAvailableInSeconds()
            );
        } catch (BusinessException e) {
            throw mapUserEmailVerificationException(e);
        }
    }

    /**
     * 이메일 인증번호 검증 상태는 user가 소유하지만 customer endpoint 응답에는 CUSTOMER-* 코드만 사용한다.
     */
    public CustomerEmailVerificationConfirmResult confirmEmailVerification(CustomerEmailVerificationConfirmCommand command) {
        try {
            SignupEmailVerificationConfirmResult result = userSignupEmailVerificationUseCase.confirmSignupEmailVerification(
                    new SignupEmailVerificationConfirmCommand(command.email(), command.code())
            );
            return new CustomerEmailVerificationConfirmResult(
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
     * 회원가입 저장 직전에 휴대폰·이메일 verified 상태를 user 단일 정책으로 함께 소비한다.
     * user 쪽 원자 소비가 한쪽 인증 누락 시 다른 쪽 인증을 보존하므로 customer는 개별 consume을 조합하지 않는다.
     */
    public void consumePhoneAndEmailVerification(String phoneNumber, String email) {
        try {
            userSignupContactVerificationUseCase.consumeSignupContactVerification(
                    new SignupContactVerificationConsumeCommand(phoneNumber, email)
            );
        } catch (BusinessException e) {
            throw mapUserContactVerificationException(e);
        }
    }

    /**
     * user-api 뒤 구현체에서 발생한 회원가입 휴대폰 인증 오류를 customer API 오류 계약으로 변환한다.
     */
    private RuntimeException mapUserPhoneVerificationException(BusinessException exception) {
        return toCustomerException(mapUserPhoneVerificationErrorCode(exception), exception);
    }

    /**
     * user-api 뒤 구현체에서 발생한 회원가입 이메일 인증 오류를 customer API 오류 계약으로 변환한다.
     */
    private RuntimeException mapUserEmailVerificationException(BusinessException exception) {
        return toCustomerException(mapUserEmailVerificationErrorCode(exception), exception);
    }

    /**
     * user의 단일 연락처 소비 use case에서 발생한 phone/email 인증 오류를 customer 오류 계약으로 변환한다.
     */
    private RuntimeException mapUserContactVerificationException(BusinessException exception) {
        CustomerErrorCode customerErrorCode = mapUserPhoneVerificationErrorCode(exception);
        if (customerErrorCode == null) {
            customerErrorCode = mapUserEmailVerificationErrorCode(exception);
        }

        return toCustomerException(customerErrorCode, exception);
    }

    private CustomerException toCustomerException(CustomerErrorCode customerErrorCode, BusinessException exception) {
        if (customerErrorCode == null) {
            return new CustomerException(CustomerErrorCode.SIGNUP_VERIFICATION_UNAVAILABLE, exception);
        }

        return new CustomerException(customerErrorCode, exception);
    }

    private CustomerErrorCode mapUserPhoneVerificationErrorCode(BusinessException exception) {
        // customer-core는 user-core에 의존할 수 없으므로 ErrorCode의 공개 문자열 계약만 보고 변환한다.
        return switch (exception.getErrorCode().getCode()) {
            case "USER-101" -> CustomerErrorCode.PHONE_VERIFICATION_REQUIRED;
            case "USER-102" -> CustomerErrorCode.PHONE_VERIFICATION_CODE_NOT_FOUND;
            case "USER-103" -> CustomerErrorCode.PHONE_VERIFICATION_CODE_MISMATCH;
            case "USER-104" -> CustomerErrorCode.PHONE_VERIFICATION_ATTEMPTS_EXCEEDED;
            case "USER-105" -> CustomerErrorCode.PHONE_VERIFICATION_COOLDOWN;
            case "USER-106" -> CustomerErrorCode.SMS_SEND_UNAVAILABLE;
            case "USER-107" -> CustomerErrorCode.DUPLICATED_PHONE_NUMBER;
            default -> null;
        };
    }

    private CustomerErrorCode mapUserEmailVerificationErrorCode(BusinessException exception) {
        // customer-core는 user-core에 의존할 수 없으므로 ErrorCode의 공개 문자열 계약만 보고 변환한다.
        return switch (exception.getErrorCode().getCode()) {
            case "USER-108" -> CustomerErrorCode.EMAIL_VERIFICATION_REQUIRED;
            case "USER-109" -> CustomerErrorCode.EMAIL_VERIFICATION_CODE_NOT_FOUND;
            case "USER-110" -> CustomerErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH;
            case "USER-111" -> CustomerErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED;
            case "USER-112" -> CustomerErrorCode.EMAIL_VERIFICATION_COOLDOWN;
            case "USER-106" -> CustomerErrorCode.EMAIL_SEND_UNAVAILABLE;
            case "USER-113" -> CustomerErrorCode.DUPLICATED_EMAIL;
            default -> null;
        };
    }
}
