package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.notification.command.SendSignupVerificationSmsCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;
import com.sapari.user.application.config.UserSignupPhoneVerificationProperties;
import com.sapari.user.application.port.VerificationCodeGenerator;
import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupPhoneVerificationConfirmCommand;
import com.sapari.user.command.SignupPhoneVerificationSendCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupPhoneVerificationRepository;
import com.sapari.user.domain.repository.SignupPhoneVerificationRepository.ConfirmResult;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.port.UserSignupPhoneVerificationUseCase;
import com.sapari.user.view.SignupPhoneVerificationConfirmResult;
import com.sapari.user.view.SignupPhoneVerificationSendResult;

/**
 * 회원가입 휴대폰 인증번호 발송·확인·소비 정책을 처리한다.
 * 인증번호 원문은 문자 발송에만 사용하고, Redis에는 codeHash와 verified 상태를 분리해 저장한다.
 */
@Service
@RequiredArgsConstructor
public class UserSignupPhoneVerificationService implements UserSignupPhoneVerificationUseCase {

    private static final int VERIFICATION_CODE_LENGTH = 6;

    private final SignupPhoneVerificationRepository repository;
    private final NotificationSendUseCase notificationSendUseCase;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;
    private final UserRepository userRepository;
    private final UserSignupPhoneVerificationProperties properties;

    /**
     * 회원가입 휴대폰 인증번호를 발송한다.
     * 가입 가능한 미등록 번호만 발송하고, provider 성공 응답을 받은 뒤에만 Redis codeHash를 저장한다.
     */
    @Override
    public SignupPhoneVerificationSendResult sendSignupPhoneVerification(SignupPhoneVerificationSendCommand command) {
        validateSignupPhoneNumberAvailable(command.phoneNumber());
        String phoneHash = codeHasher.hashPhoneNumber(command.phoneNumber());
        String cooldownToken = UUID.randomUUID().toString();
        acquireCooldownOrThrow(phoneHash, cooldownToken);

        String code = codeGenerator.generateNumericCode(VERIFICATION_CODE_LENGTH);
        sendVerificationCode(command.phoneNumber(), code, phoneHash, cooldownToken);

        String codeHash = codeHasher.hashCode(command.phoneNumber(), code);
        saveIssuedCode(phoneHash, codeHash);

        return new SignupPhoneVerificationSendResult(
                true,
                properties.getCodeTtl().toSeconds(),
                properties.getResendCooldown().toSeconds()
        );
    }

    /**
     * 회원가입 휴대폰 인증번호를 확인한다.
     * codeHash가 일치하면 기존 code/fail 상태를 삭제하고, 회원가입 API가 소비할 verified 상태를 저장한다.
     */
    @Override
    public SignupPhoneVerificationConfirmResult confirmSignupPhoneVerification(SignupPhoneVerificationConfirmCommand command) {
        String phoneHash = codeHasher.hashPhoneNumber(command.phoneNumber());
        String requestedCodeHash = codeHasher.hashCode(command.phoneNumber(), command.code());
        ConfirmResult confirmResult = repository.confirmCode(
                phoneHash,
                requestedCodeHash,
                properties.getCodeTtl(),
                properties.getVerifiedTtl(),
                properties.getMaxAttempts()
        );

        validateConfirmResult(confirmResult);
        return new SignupPhoneVerificationConfirmResult(true, properties.getVerifiedTtl().toSeconds());
    }

    /**
     * 회원가입 API가 사용할 휴대폰 인증 완료 상태를 소비한다.
     * 프론트의 phoneVerified 값은 위조 가능하므로 Redis verified 상태만 서버 기준으로 한 번 소비한다.
     */
    @Override
    public void consumeSignupPhoneVerification(String phoneNumber) {
        String phoneHash = codeHasher.hashPhoneNumber(phoneNumber);
        validateVerifiedConsumed(phoneHash);
    }

    /**
     * 회원가입용 SMS는 최종 가입 가능한 미등록 번호에만 발송한다.
     * 이미 가입된 번호는 가입이 불가능하므로 기존 회원 SMS 스팸과 SOLAPI 과금을 줄이기 위해 발송 전에 차단한다.
     */
    private void validateSignupPhoneNumberAvailable(String phoneNumber) {
        if (userRepository.existsByPhoneNumber(phoneNumber)) {
            throw new UserException(UserErrorCode.DUPLICATED_PHONE_NUMBER);
        }
    }

    /**
     * 재발송 제한은 SMS 발송 전에 먼저 선점한다.
     * provider 호출보다 앞에서 차단해 반복 발송 비용과 짧은 시간 내 brute-force 시도를 함께 줄인다.
     */
    private void acquireCooldownOrThrow(String phoneHash, String cooldownToken) {
        if (!repository.acquireCooldown(phoneHash, cooldownToken, properties.getResendCooldown())) {
            throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_COOLDOWN);
        }
    }

    /**
     * notification 발송 성공 응답을 받은 경우에만 이후 Redis 인증 상태를 저장한다.
     * 발송 실패 시에는 선점한 cooldown을 해제해 사용자가 다시 시도할 수 있게 한다.
     */
    private void sendVerificationCode(String phoneNumber, String code, String phoneHash, String cooldownToken) {
        try {
            MessageSendResult sendResult = notificationSendUseCase.sendSignupVerificationSms(
                    new SendSignupVerificationSmsCommand(phoneNumber, code)
            );
            validateSendResult(sendResult);
        } catch (UserException e) {
            releaseCooldownAfterSendFailure(phoneHash, cooldownToken);
            throw e;
        } catch (RuntimeException e) {
            releaseCooldownAfterSendFailure(phoneHash, cooldownToken);
            throw new UserException(UserErrorCode.SIGNUP_VERIFICATION_SEND_UNAVAILABLE, e);
        }
    }

    /**
     * provider가 명시적으로 실패를 반환한 경우에도 발송 실패 정책으로 통일한다.
     * 사용자는 provider 세부 사유가 아니라 재시도 가능한 서비스 지연으로만 안내받는다.
     */
    private void validateSendResult(MessageSendResult sendResult) {
        if (!sendResult.success()) {
            throw new UserException(UserErrorCode.SIGNUP_VERIFICATION_SEND_UNAVAILABLE);
        }
    }

    /**
     * 발송 실패 보상은 현재 요청이 만든 cooldownToken과 일치할 때만 적용된다.
     * 늦게 실패한 요청이 이후 요청의 쿨다운을 지우는 경쟁 상태를 막기 위한 방어다.
     */
    private void releaseCooldownAfterSendFailure(String phoneHash, String cooldownToken) {
        repository.releaseCooldown(phoneHash, cooldownToken);
    }

    /**
     * 발송 성공 후 사용자가 받은 code만 Redis에 유효한 codeHash로 저장한다.
     * 새 code를 받은 경우에만 실패 횟수를 초기화하며, 발송 실패 시에는 기존 시도 상태를 유지한다.
     */
    private void saveIssuedCode(String phoneHash, String codeHash) {
        repository.saveCode(phoneHash, codeHash, properties.getCodeTtl());
        repository.deleteFailures(phoneHash);
    }

    /**
     * Redis Lua confirm 결과를 user-api 오류 계약으로 변환한다.
     * code/fail 삭제와 verified 저장은 이미 원자 script 안에서 끝났으므로 여기서는 상태를 다시 변경하지 않는다.
     */
    private void validateConfirmResult(ConfirmResult confirmResult) {
        switch (confirmResult) {
            case VERIFIED -> {
            }
            case CODE_NOT_FOUND -> throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_CODE_NOT_FOUND);
            case ATTEMPTS_EXCEEDED -> throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_ATTEMPTS_EXCEEDED);
            case CODE_MISMATCH -> throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_CODE_MISMATCH);
        }
    }

    /**
     * verified 상태는 가입 저장 직전에 한 번만 소비한다.
     * 동일 인증 결과로 여러 계정을 만들거나 실패한 가입 요청을 재사용하는 것을 막는다.
     */
    private void validateVerifiedConsumed(String phoneHash) {
        if (!repository.consumeVerified(phoneHash)) {
            throw new UserException(UserErrorCode.SIGNUP_PHONE_VERIFICATION_REQUIRED);
        }
    }
}
