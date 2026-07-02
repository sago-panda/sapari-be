package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupContactVerificationConsumeCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupContactVerificationRepository;
import com.sapari.user.port.UserSignupContactVerificationUseCase;

/**
 * 회원가입 최종 저장 전에 필요한 휴대폰·이메일 verified 상태 소비 정책을 처리한다.
 */
@Service
@RequiredArgsConstructor
public class UserSignupContactVerificationService implements UserSignupContactVerificationUseCase {

    private final SignupContactVerificationRepository repository;
    private final VerificationCodeHasher codeHasher;

    /**
     * 휴대폰·이메일 verified 상태를 같은 Redis 원자 연산으로 소비한다.
     * 한쪽만 만료된 경우 다른 쪽 인증을 보존해 사용자가 불필요하게 재인증하지 않도록 한다.
     */
    @Override
    public void consumeSignupContactVerification(SignupContactVerificationConsumeCommand command) {
        String phoneHash = codeHasher.hashPhoneNumber(command.phoneNumber());
        String emailHash = codeHasher.hashEmail(command.email());

        SignupContactVerificationRepository.ConsumeResult result = repository.consumeVerified(phoneHash, emailHash);
        validateConsumed(result);
    }

    /**
     * Redis 소비 결과를 user 도메인의 기존 인증 필요 오류 계약으로 변환한다.
     */
    private void validateConsumed(SignupContactVerificationRepository.ConsumeResult result) {
        switch (result) {
            case CONSUMED -> {
            }
            case PHONE_MISSING -> throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_REQUIRED);
            case EMAIL_MISSING -> throw new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_REQUIRED);
        }
    }
}
