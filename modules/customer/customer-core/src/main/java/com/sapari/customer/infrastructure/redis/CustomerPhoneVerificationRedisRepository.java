package com.sapari.customer.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.customer.domain.repository.CustomerPhoneVerificationRepository;

/**
 * 구매자 회원가입 휴대폰 인증 상태를 Redis에 저장한다.
 * code, verified, fail, cooldown key를 분리해 인증번호 검증과 회원가입 완료 검증을 서로 다른 상태로 관리한다.
 */
@Repository
@RequiredArgsConstructor
public class CustomerPhoneVerificationRedisRepository implements CustomerPhoneVerificationRepository {

    private static final String SIGN_UP_NAMESPACE = "SIGN_UP";
    private static final String CODE_KEY_PREFIX = "sms:code";
    private static final String VERIFIED_KEY_PREFIX = "sms:verified";
    private static final String FAILURE_KEY_PREFIX = "sms:fail";
    private static final String COOLDOWN_KEY_PREFIX = "sms:cooldown";
    private static final String VERIFIED_VALUE = "true";
    /**
     * SMS 발송 실패 보상 시 이전 요청이 이후 요청의 cooldown을 지우지 않도록
     * 쿨다운 선점 식별자(cooldownToken)를 Redis 안에서 비교 후 삭제한다.
     * GET 후 DEL을 분리하면 실패한 이전 요청이 그 사이 새 요청의 cooldown을 지울 수 있으므로
     * 비교와 삭제를 Lua 스크립트 하나로 묶어 원자적으로 처리한다.
     */
    private static final DefaultRedisScript<Long> RELEASE_COOLDOWN_SCRIPT = new DefaultRedisScript<>(
            """
            local currentCooldownToken = redis.call('get', KEYS[1])
            local expectedCooldownToken = ARGV[1]

            if currentCooldownToken == expectedCooldownToken then
                return redis.call('del', KEYS[1])
            end

            return 0
            """,
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 인증번호 원문 노출을 줄이기 위해 codeHash만 TTL과 함께 저장한다.
     */
    @Override
    public void saveCode(String phoneHash, String codeHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(codeKey(phoneHash), codeHash, ttl);
    }

    /**
     * 사용자가 입력한 인증번호의 해시와 비교할 저장된 codeHash를 조회한다.
     */
    @Override
    public Optional<String> findCodeHash(String phoneHash) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(codeKey(phoneHash)));
    }

    /**
     * 인증 성공 또는 5회 실패 시 같은 인증번호로 더 이상 검증하지 못하도록 code와 fail key를 함께 지운다.
     */
    @Override
    public void deleteCodeAndFailures(String phoneHash) {
        stringRedisTemplate.delete(List.of(
                codeKey(phoneHash),
                failureKey(phoneHash)
        ));
    }

    /**
     * 인증번호 확인 성공 후 회원가입 API가 소비할 서버 기준 verified 상태를 저장한다.
     */
    @Override
    public void saveVerified(String phoneHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(verifiedKey(phoneHash), VERIFIED_VALUE, ttl);
    }

    /**
     * 회원가입 요청에서 verified 상태를 한 번만 사용할 수 있도록 Redis GETDEL로 원자 소비한다.
     */
    @Override
    public boolean consumeVerified(String phoneHash) {
        return VERIFIED_VALUE.equals(stringRedisTemplate.opsForValue().getAndDelete(verifiedKey(phoneHash)));
    }

    /**
     * Redis SET NX EX로 쿨다운을 선점해 같은 번호의 병렬 SMS 발송을 막는다.
     */
    @Override
    public boolean acquireCooldown(String phoneHash, String cooldownToken, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(phoneHash), cooldownToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * provider 발송 실패 시 내가 선점했던 쿨다운만 제거한다.
     */
    @Override
    public void releaseCooldown(String phoneHash, String cooldownToken) {
        stringRedisTemplate.execute(RELEASE_COOLDOWN_SCRIPT, List.of(cooldownKey(phoneHash)), cooldownToken);
    }

    /**
     * 인증번호 불일치 횟수를 증가시키고 첫 실패부터 code TTL과 같은 만료 시간을 적용한다.
     */
    @Override
    public long incrementFailure(String phoneHash, Duration ttl) {
        String key = failureKey(phoneHash);
        Long failureCount = stringRedisTemplate.opsForValue().increment(key);
        if (Long.valueOf(1L).equals(failureCount)) {
            stringRedisTemplate.expire(key, ttl);
        }
        return failureCount == null ? 0L : failureCount;
    }

    private String codeKey(String phoneHash) {
        return key(CODE_KEY_PREFIX, phoneHash);
    }

    private String verifiedKey(String phoneHash) {
        return key(VERIFIED_KEY_PREFIX, phoneHash);
    }

    private String failureKey(String phoneHash) {
        return key(FAILURE_KEY_PREFIX, phoneHash);
    }

    private String cooldownKey(String phoneHash) {
        return key(COOLDOWN_KEY_PREFIX, phoneHash);
    }

    private String key(String prefix, String phoneHash) {
        return prefix + ":" + SIGN_UP_NAMESPACE + ":" + phoneHash;
    }
}
