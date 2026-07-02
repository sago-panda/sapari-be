package com.sapari.user.domain.repository;

import java.time.Duration;
/**
 * 회원가입 이메일 인증 Redis 상태 저장소 port다.
 * code/verified/fail/cooldown을 분리해 발송 상태와 가입 허용 상태를 혼동하지 않게 한다.
 */
public interface SignupEmailVerificationRepository {

    enum ConfirmResult {
        VERIFIED,
        CODE_NOT_FOUND,
        CODE_MISMATCH,
        ATTEMPTS_EXCEEDED
    }

    /**
     * 인증번호 확인 1회를 Redis 원자 연산으로 처리한다.
     * code 확인, 실패 횟수 증가/폐기, 성공 시 verified 저장을 하나의 상태 전이로 묶는다.
     */
    ConfirmResult confirmCode(
            String emailHash,
            String requestedCodeHash,
            Duration codeTtl,
            Duration verifiedTtl,
            int maxAttempts
    );

    /**
     * provider 발송 성공 후에만 codeHash를 TTL과 함께 저장한다.
     */
    void saveCode(String emailHash, String codeHash, Duration ttl);

    /**
     * 새 인증번호가 정상 발송된 경우 이전 실패 횟수만 초기화한다.
     */
    void deleteFailures(String emailHash);

    /**
     * verified 상태를 한 번만 소비한다.
     * 가입 요청 재전송이나 인증 완료 플래그 위조가 같은 인증 상태를 재사용하지 못하게 한다.
     */
    boolean consumeVerified(String emailHash);

    /**
     * 같은 이메일로 인증번호 발송을 반복 요청하지 못하도록 쿨다운 key를 선점한다.
     */
    boolean acquireCooldown(String emailHash, String cooldownToken, Duration ttl);

    /**
     * email provider 실패 보상 시 현재 요청이 선점한 쿨다운만 해제한다.
     */
    void releaseCooldown(String emailHash, String cooldownToken);
}
