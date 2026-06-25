package com.sapari.user.domain.repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 회원가입 휴대폰 인증번호, 인증 완료 상태, 실패 횟수, 재요청 쿨다운을 저장한다.
 * application service가 Redis key 구조를 직접 알지 않도록 인증 상태 저장 정책을 캡슐화한다.
 */
public interface SignupPhoneVerificationRepository {

    /**
     * provider 발송 성공 후에만 codeHash를 TTL과 함께 저장한다.
     */
    void saveCode(String phoneHash, String codeHash, Duration ttl);

    /**
     * 아직 확인되지 않은 인증번호의 hash를 조회한다.
     */
    Optional<String> findCodeHash(String phoneHash);

    /**
     * 인증 성공 또는 최대 실패 횟수 초과 시 재사용 가능한 code와 실패 카운트를 함께 제거한다.
     */
    void deleteCodeAndFailures(String phoneHash);

    /**
     * 새 인증번호가 정상 발송된 경우 이전 실패 횟수만 초기화한다.
     */
    void deleteFailures(String phoneHash);

    /**
     * 인증번호 확인 성공 후 회원가입 API가 소비할 verified 상태를 TTL과 함께 저장한다.
     */
    void saveVerified(String phoneHash, Duration ttl);

    /**
     * verified 상태를 한 번만 소비한다.
     * 가입 요청 재전송이나 인증 완료 플래그 위조가 같은 인증 상태를 재사용하지 못하게 한다.
     */
    boolean consumeVerified(String phoneHash);

    /**
     * 같은 번호로 인증번호 발송을 반복 요청하지 못하도록 쿨다운 key를 선점한다.
     */
    boolean acquireCooldown(String phoneHash, String cooldownToken, Duration ttl);

    /**
     * SMS provider 실패 보상 시 현재 요청이 선점한 쿨다운만 해제한다.
     */
    void releaseCooldown(String phoneHash, String cooldownToken);

    /**
     * 인증번호 불일치 횟수를 TTL 안에서 누적해 brute-force 입력을 제한한다.
     */
    long incrementFailure(String phoneHash, Duration ttl);
}
