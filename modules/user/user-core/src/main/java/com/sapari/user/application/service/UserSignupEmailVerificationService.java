package com.sapari.user.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.notification.command.SendSignupVerificationEmailCommand;
import com.sapari.notification.port.NotificationSendUseCase;
import com.sapari.notification.view.MessageSendResult;
import com.sapari.user.application.config.UserSignupEmailVerificationProperties;
import com.sapari.user.application.port.VerificationCodeGenerator;
import com.sapari.user.application.port.VerificationCodeHasher;
import com.sapari.user.command.SignupEmailVerificationConfirmCommand;
import com.sapari.user.command.SignupEmailVerificationSendCommand;
import com.sapari.user.domain.exception.UserErrorCode;
import com.sapari.user.domain.exception.UserException;
import com.sapari.user.domain.repository.SignupEmailVerificationRepository;
import com.sapari.user.domain.repository.UserRepository;
import com.sapari.user.port.UserSignupEmailVerificationUseCase;
import com.sapari.user.view.SignupEmailVerificationConfirmResult;
import com.sapari.user.view.SignupEmailVerificationSendResult;

/**
 * 회원가입 이메일 인증번호 발송·확인·소비 정책을 처리한다.
 * 인증번호 원문은 메일 발송에만 사용하고, Redis에는 emailHash/codeHash와 verified 상태를 분리해 저장한다.
 */
@Service
@RequiredArgsConstructor
public class UserSignupEmailVerificationService implements UserSignupEmailVerificationUseCase {

    private static final int VERIFICATION_CODE_LENGTH = 6;

    private final SignupEmailVerificationRepository repository;
    private final NotificationSendUseCase notificationSendUseCase;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;
    private final UserRepository userRepository;
    private final UserSignupEmailVerificationProperties properties;

    /**
     * 회원가입 이메일 인증번호를 발송한다.
     * 가입 가능한 미등록 이메일만 발송하고, provider 성공 응답을 받은 뒤에만 Redis codeHash를 저장한다.
     */
    @Override
    public SignupEmailVerificationSendResult sendSignupEmailVerification(SignupEmailVerificationSendCommand command) {
        validateSignupEmailAvailable(command.email());
        String emailHash = codeHasher.hashEmail(command.email());
        String cooldownToken = UUID.randomUUID().toString();
        acquireCooldownOrThrow(emailHash, cooldownToken);

        String code = codeGenerator.generateNumericCode(VERIFICATION_CODE_LENGTH);
        sendVerificationCode(command.email(), code, emailHash, cooldownToken);

        String codeHash = codeHasher.hashEmailCode(command.email(), code);
        saveIssuedCode(emailHash, codeHash);

        return new SignupEmailVerificationSendResult(
                true,
                properties.getCodeTtl().toSeconds(),
                properties.getResendCooldown().toSeconds()
        );
    }

    /**
     * 회원가입 이메일 인증번호를 확인한다.
     * codeHash가 일치하면 기존 code/fail 상태를 삭제하고, 회원가입 API가 소비할 verified 상태를 저장한다.
     */
    @Override
    public SignupEmailVerificationConfirmResult confirmSignupEmailVerification(SignupEmailVerificationConfirmCommand command) {
        String emailHash = codeHasher.hashEmail(command.email());
        String storedCodeHash = findStoredCodeHash(emailHash);
        String requestedCodeHash = codeHasher.hashEmailCode(command.email(), command.code());

        validateCodeMatch(emailHash, storedCodeHash, requestedCodeHash);
        saveVerified(emailHash);

        return new SignupEmailVerificationConfirmResult(true, properties.getVerifiedTtl().toSeconds());
    }

    /**
     * 회원가입 API가 사용할 이메일 인증 완료 상태를 소비한다.
     * 프론트의 emailVerified 값은 위조 가능하므로 Redis verified 상태만 서버 기준으로 한 번 소비한다.
     */
    @Override
    public void consumeSignupEmailVerification(String email) {
        String emailHash = codeHasher.hashEmail(email);
        validateVerifiedConsumed(emailHash);
    }

    /**
     * 회원가입용 이메일은 최종 가입 가능한 미등록 이메일에만 발송한다.
     * 이미 가입된 이메일은 가입이 불가능하므로 기존 회원 스팸과 Resend 과금을 줄이기 위해 발송 전에 차단한다.
     */
    private void validateSignupEmailAvailable(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new UserException(UserErrorCode.DUPLICATED_EMAIL);
        }
    }

    /**
     * 재발송 제한은 이메일 발송 전에 먼저 선점한다.
     * provider 호출보다 앞에서 차단해 반복 발송 비용과 짧은 시간 내 brute-force 시도를 함께 줄인다.
     */
    private void acquireCooldownOrThrow(String emailHash, String cooldownToken) {
        if (!repository.acquireCooldown(emailHash, cooldownToken, properties.getResendCooldown())) {
            throw new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_COOLDOWN);
        }
    }

    /**
     * notification 발송 성공 응답을 받은 경우에만 이후 Redis 인증 상태를 저장한다.
     * 발송 실패 시에는 선점한 cooldown을 해제해 사용자가 다시 시도할 수 있게 한다.
     */
    private void sendVerificationCode(String email, String code, String emailHash, String cooldownToken) {
        try {
            MessageSendResult sendResult = notificationSendUseCase.sendSignupVerificationEmail(
                    new SendSignupVerificationEmailCommand(email, code)
            );
            validateSendResult(sendResult);
        } catch (UserException e) {
            releaseCooldownAfterSendFailure(emailHash, cooldownToken);
            throw e;
        } catch (RuntimeException e) {
            releaseCooldownAfterSendFailure(emailHash, cooldownToken);
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
    private void releaseCooldownAfterSendFailure(String emailHash, String cooldownToken) {
        repository.releaseCooldown(emailHash, cooldownToken);
    }

    /**
     * 발송 성공 후 사용자가 받은 code만 Redis에 유효한 codeHash로 저장한다.
     * 새 code를 받은 경우에만 실패 횟수를 초기화하며, 발송 실패 시에는 기존 시도 상태를 유지한다.
     */
    private void saveIssuedCode(String emailHash, String codeHash) {
        repository.saveCode(emailHash, codeHash, properties.getCodeTtl());
        repository.deleteFailures(emailHash);
    }

    /**
     * Redis에 유효한 codeHash가 없으면 만료되었거나 발급되지 않은 인증번호로 보고 재발급을 요구한다.
     */
    private String findStoredCodeHash(String emailHash) {
        return repository.findCodeHash(emailHash)
                .orElseThrow(() -> new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_CODE_NOT_FOUND));
    }

    /**
     * 인증번호 원문은 저장하지 않고 요청 시점에 다시 hash해 저장된 codeHash와 비교한다.
     * 불일치 시도는 TTL 안에서 누적해 짧은 숫자 코드에 대한 반복 대입을 제한한다.
     */
    private void validateCodeMatch(String emailHash, String storedCodeHash, String requestedCodeHash) {
        if (storedCodeHash.equals(requestedCodeHash)) {
            return;
        }

        long failedAttempts = repository.incrementFailure(emailHash, properties.getCodeTtl());
        validateFailureAttempts(emailHash, failedAttempts);
        throw new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_CODE_MISMATCH);
    }

    /**
     * 최대 실패 횟수에 도달하면 기존 codeHash를 폐기한다.
     * 같은 인증번호로 계속 시도하지 못하게 하고, 사용자는 새 인증번호를 다시 발급받아야 한다.
     */
    private void validateFailureAttempts(String emailHash, long failedAttempts) {
        if (failedAttempts < properties.getMaxAttempts()) {
            return;
        }

        repository.deleteCodeAndFailures(emailHash);
        throw new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
    }

    /**
     * 인증번호 검증이 끝나면 code/fail 상태를 제거하고 가입 완료 단계가 소비할 verified 상태만 남긴다.
     * 검증 성공과 가입 완료를 분리해 구매자/판매자 추가정보 입력 흐름을 지원한다.
     */
    private void saveVerified(String emailHash) {
        repository.deleteCodeAndFailures(emailHash);
        repository.saveVerified(emailHash, properties.getVerifiedTtl());
    }

    /**
     * verified 상태는 가입 저장 직전에 한 번만 소비한다.
     * 동일 인증 결과로 여러 계정을 만들거나 실패한 가입 요청을 재사용하는 것을 막는다.
     */
    private void validateVerifiedConsumed(String emailHash) {
        if (!repository.consumeVerified(emailHash)) {
            throw new UserException(UserErrorCode.SIGNUP_EMAIL_VERIFICATION_REQUIRED);
        }
    }
}
