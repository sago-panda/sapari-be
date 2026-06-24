package com.sapari.customer.domain.repository;

import java.time.Duration;
import java.util.Optional;

/**
 * 구매자 휴대폰 인증번호, 인증 완료 상태, 실패 횟수, 재요청 쿨다운을 저장한다.
 * application service가 Redis key 구조를 직접 알지 않도록 인증 상태 저장 정책을 캡슐화한다.
 */
public interface CustomerPhoneVerificationRepository {

    /**
     * 인증번호 해시를 전화번호별 code key에 TTL과 함께 저장한다.
     */
    void saveCode(String phoneHash, String codeHash, Duration ttl);

    /**
     * 인증번호 확인을 위해 저장된 codeHash를 조회한다.
     */
    Optional<String> findCodeHash(String phoneHash);

    /**
     * 5회 실패 또는 인증 성공 시 기존 code와 실패 횟수를 함께 삭제한다.
     */
    void deleteCodeAndFailures(String phoneHash);

    /**
     * 새 인증번호 재발급 시 이전 인증번호의 실패 횟수만 초기화한다.
     * 실패 횟수는 전화번호 잠금이 아니라 현재 발급된 code 단위의 무차별 대입 방어이므로,
     * 재발급된 새 code에 이전 code의 실패 횟수가 이어지면 안 된다.
     */
    void deleteFailures(String phoneHash);

    /**
     * 인증 성공 후 회원가입 API가 소비할 verified 상태를 TTL과 함께 저장한다.
     */
    void saveVerified(String phoneHash, Duration ttl);

    /**
     * verified 상태를 한 번만 사용할 수 있도록 읽는 동시에 삭제한다.
     */
    boolean consumeVerified(String phoneHash);

    /**
     * 쿨다운 key를 원자적으로 선점한다. 이미 key가 있으면 발송 권한을 얻지 못한다.
     * 발송 실패 보상 시 다른 요청의 쿨다운을 지우지 않도록 선점 token을 value로 저장한다.
     */
    boolean acquireCooldown(String phoneHash, String cooldownToken, Duration ttl);

    /**
     * SMS 발송 실패 시 자신이 선점한 쿨다운만 해제해 사용자가 재시도할 수 있게 한다.
     */
    void releaseCooldown(String phoneHash, String cooldownToken);

    /**
     * 인증번호 불일치 횟수를 증가시키고 첫 실패 시 TTL을 설정한다.
     */
    long incrementFailure(String phoneHash, Duration ttl);
}
