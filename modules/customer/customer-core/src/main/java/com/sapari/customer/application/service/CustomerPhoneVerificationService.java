package com.sapari.customer.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.customer.application.config.CustomerPhoneVerificationProperties;
import com.sapari.customer.application.port.SmsSendResult;
import com.sapari.customer.application.port.SmsSender;
import com.sapari.customer.command.CustomerPhoneVerificationConfirmCommand;
import com.sapari.customer.command.CustomerPhoneVerificationSendCommand;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.CustomerPhoneVerificationRepository;
import com.sapari.customer.view.CustomerPhoneVerificationConfirmResult;
import com.sapari.customer.view.CustomerPhoneVerificationSendResult;

/**
 * 구매자 회원가입 휴대폰 인증번호 발송·확인·소비 정책을 처리한다.
 * 인증번호 원문은 문자 발송에만 사용하고, Redis에는 codeHash와 verified 상태를 분리해 저장한다.
 */
@Service
@RequiredArgsConstructor
public class CustomerPhoneVerificationService {

    private static final int VERIFICATION_CODE_LENGTH = 6;

    private final CustomerPhoneVerificationRepository repository;
    private final SmsSender smsSender;
    private final VerificationCodeGenerator codeGenerator;
    private final VerificationCodeHasher codeHasher;
    private final CustomerPhoneVerificationProperties properties;

    /**
     * 구매자 회원가입 휴대폰 인증번호를 발송한다.
     * Redis cooldown을 먼저 원자 선점한 요청만 SOLAPI 발송을 진행하고, 발송 실패 시 선점한 cooldown을 해제한다.
     *
     * @throws CustomerException 재요청 쿨다운 중이거나 문자 발송이 실패한 경우
     */
    public CustomerPhoneVerificationSendResult sendSignupCode(CustomerPhoneVerificationSendCommand command) {
        String phoneHash = codeHasher.hashPhoneNumber(command.phoneNumber());
        String cooldownToken = UUID.randomUUID().toString();
        acquireCooldownOrThrow(phoneHash, cooldownToken);

        String code = codeGenerator.generateNumericCode(VERIFICATION_CODE_LENGTH);
        sendVerificationCode(command.phoneNumber(), code, phoneHash, cooldownToken);

        String codeHash = codeHasher.hashCode(command.phoneNumber(), code);
        saveIssuedCode(phoneHash, codeHash);

        return new CustomerPhoneVerificationSendResult(
                true,
                properties.getCodeTtl().toSeconds(),
                properties.getResendCooldown().toSeconds()
        );
    }

    /**
     * 구매자 회원가입 휴대폰 인증번호를 확인한다.
     * codeHash가 일치하면 기존 code/fail 상태를 삭제하고, 회원가입 API가 소비할 verified 상태를 저장한다.
     *
     * @throws CustomerException 인증번호가 없거나, 불일치하거나, 실패 횟수를 초과한 경우
     */
    public CustomerPhoneVerificationConfirmResult confirmSignupCode(CustomerPhoneVerificationConfirmCommand command) {
        String phoneHash = codeHasher.hashPhoneNumber(command.phoneNumber());
        String storedCodeHash = findStoredCodeHash(phoneHash);
        String requestedCodeHash = codeHasher.hashCode(command.phoneNumber(), command.code());

        validateCodeMatch(phoneHash, storedCodeHash, requestedCodeHash);
        saveVerified(phoneHash);

        return new CustomerPhoneVerificationConfirmResult(true, properties.getVerifiedTtl().toSeconds());
    }

    /**
     * 구매자 회원가입 API가 사용할 휴대폰 인증 완료 상태를 소비한다.
     * 프론트의 phoneVerified 값은 위조 가능하므로 Redis verified 상태만 서버 기준으로 한 번 소비한다.
     *
     * @throws CustomerException verified 상태가 없거나 이미 소비된 경우
     */
    public void consumeSignupVerification(String phoneNumber) {
        String phoneHash = codeHasher.hashPhoneNumber(phoneNumber);
        validateVerifiedConsumed(phoneHash);
    }

    /**
     * 같은 휴대폰 번호의 병렬 요청 중 하나만 SMS 발송 권한을 얻도록 Redis cooldown을 원자 선점한다.
     */
    private void acquireCooldownOrThrow(String phoneHash, String cooldownToken) {
        if (!repository.acquireCooldown(phoneHash, cooldownToken, properties.getResendCooldown())) {
            throw new CustomerException(CustomerErrorCode.PHONE_VERIFICATION_COOLDOWN);
        }
    }

    /**
     * SMS 발송 adapter의 성공 응답을 받은 경우에만 이후 Redis 인증 상태를 저장한다.
     * 발송 실패 시에는 선점한 cooldown을 해제해 사용자가 다시 시도할 수 있게 한다.
     */
    private void sendVerificationCode(String phoneNumber, String code, String phoneHash, String cooldownToken) {
        try {
            SmsSendResult sendResult = smsSender.sendVerificationCode(phoneNumber, code);
            validateSmsSendResult(sendResult);
        } catch (CustomerException e) {
            releaseCooldownAfterSendFailure(phoneHash, cooldownToken);
            throw e;
        } catch (RuntimeException e) {
            releaseCooldownAfterSendFailure(phoneHash, cooldownToken);
            throw new CustomerException(CustomerErrorCode.SMS_SEND_UNAVAILABLE, e);
        }
    }

    /**
     * 외부 SMS provider 실패 상세는 노출하지 않고, 휴대폰 인증 도메인의 발송 불가 오류로 변환한다.
     */
    private void validateSmsSendResult(SmsSendResult sendResult) {
        if (!sendResult.success()) {
            throw new CustomerException(CustomerErrorCode.SMS_SEND_UNAVAILABLE);
        }
    }

    /**
     * SOLAPI 발송 실패 시 사용자가 재시도할 수 있도록 발송 전에 선점한 cooldown을 제거한다.
     */
    private void releaseCooldownAfterSendFailure(String phoneHash, String cooldownToken) {
        repository.releaseCooldown(phoneHash, cooldownToken);
    }

    /**
     * SOLAPI 발송 성공 후 사용자가 받은 code만 Redis에 유효한 codeHash로 저장한다.
     * 새 code를 받은 경우에만 실패 횟수를 초기화하며, 발송 실패 시에는 기존 시도 상태를 유지한다.
     * 5회 실패 정책은 전화번호 잠금이 아니라 현재 code 폐기 정책이므로 이전 실패 횟수를 새 code에 넘기지 않는다.
     */
    private void saveIssuedCode(String phoneHash, String codeHash) {
        repository.saveCode(phoneHash, codeHash, properties.getCodeTtl());
        repository.deleteFailures(phoneHash);
    }

    /**
     * code TTL이 만료됐거나 새 인증번호 발급으로 기존 code가 사라진 경우 인증을 진행하지 않는다.
     */
    private String findStoredCodeHash(String phoneHash) {
        return repository.findCodeHash(phoneHash)
                .orElseThrow(() -> new CustomerException(CustomerErrorCode.PHONE_VERIFICATION_CODE_NOT_FOUND));
    }

    /**
     * 인증번호 불일치 시 실패 횟수를 먼저 기록해 무차별 대입 시도를 제한한다.
     */
    private void validateCodeMatch(String phoneHash, String storedCodeHash, String requestedCodeHash) {
        if (storedCodeHash.equals(requestedCodeHash)) {
            return;
        }

        long failedAttempts = repository.incrementFailure(phoneHash, properties.getCodeTtl());
        validateFailureAttempts(phoneHash, failedAttempts);
        throw new CustomerException(CustomerErrorCode.PHONE_VERIFICATION_CODE_MISMATCH);
    }

    /**
     * 최대 실패 횟수에 도달한 code는 폐기해 같은 인증번호로 추가 시도할 수 없게 한다.
     */
    private void validateFailureAttempts(String phoneHash, long failedAttempts) {
        if (failedAttempts < properties.getMaxAttempts()) {
            return;
        }

        repository.deleteCodeAndFailures(phoneHash);
        throw new CustomerException(CustomerErrorCode.PHONE_VERIFICATION_ATTEMPTS_EXCEEDED);
    }

    /**
     * 인증 성공 후 code/fail 상태를 제거하고, 회원가입 API가 1회 소비할 verified 상태만 남긴다.
     */
    private void saveVerified(String phoneHash) {
        repository.deleteCodeAndFailures(phoneHash);
        repository.saveVerified(phoneHash, properties.getVerifiedTtl());
    }

    /**
     * 프론트 입력값 대신 Redis verified 상태를 기준으로 회원가입 직전에 인증 완료 여부를 1회성으로 확인한다.
     */
    private void validateVerifiedConsumed(String phoneHash) {
        if (!repository.consumeVerified(phoneHash)) {
            throw new CustomerException(CustomerErrorCode.PHONE_VERIFICATION_REQUIRED);
        }
    }
}
