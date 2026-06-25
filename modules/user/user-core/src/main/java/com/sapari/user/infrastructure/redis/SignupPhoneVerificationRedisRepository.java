package com.sapari.user.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.user.domain.repository.SignupPhoneVerificationRepository;

/**
 * 회원가입 휴대폰 인증 상태를 Redis에 저장한다.
 * code, verified, fail, cooldown key를 분리해 인증번호 검증과 회원가입 완료 검증을 서로 다른 상태로 관리한다.
 */
@Repository
@RequiredArgsConstructor
public class SignupPhoneVerificationRedisRepository implements SignupPhoneVerificationRepository {

    private static final String SIGN_UP_NAMESPACE = "SIGN_UP";
    private static final String CODE_KEY_PREFIX = "sms:code";
    private static final String VERIFIED_KEY_PREFIX = "sms:verified";
    private static final String FAILURE_KEY_PREFIX = "sms:fail";
    private static final String COOLDOWN_KEY_PREFIX = "sms:cooldown";
    private static final String VERIFIED_VALUE = "true";

    /**
     * 발송 실패 보상 시 cooldownToken이 일치하는 key만 삭제한다.
     * GET 후 DEL을 분리하면 뒤따른 재요청의 쿨다운을 지울 수 있어 Lua로 비교·삭제를 원자화한다.
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

    @Override
    public void saveCode(String phoneHash, String codeHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(codeKey(phoneHash), codeHash, ttl);
    }

    @Override
    public Optional<String> findCodeHash(String phoneHash) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(codeKey(phoneHash)));
    }

    @Override
    public void deleteCodeAndFailures(String phoneHash) {
        stringRedisTemplate.delete(List.of(
                codeKey(phoneHash),
                failureKey(phoneHash)
        ));
    }

    @Override
    public void deleteFailures(String phoneHash) {
        stringRedisTemplate.delete(failureKey(phoneHash));
    }

    @Override
    public void saveVerified(String phoneHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(verifiedKey(phoneHash), VERIFIED_VALUE, ttl);
    }

    /**
     * Redis GETDEL로 인증 완료 상태를 읽는 동시에 삭제한다.
     * 같은 verified key가 가입 재시도나 병렬 요청에서 두 번 사용되지 않도록 하는 핵심 원자 연산이다.
     */
    @Override
    public boolean consumeVerified(String phoneHash) {
        return VERIFIED_VALUE.equals(stringRedisTemplate.opsForValue().getAndDelete(verifiedKey(phoneHash)));
    }

    @Override
    public boolean acquireCooldown(String phoneHash, String cooldownToken, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(cooldownKey(phoneHash), cooldownToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public void releaseCooldown(String phoneHash, String cooldownToken) {
        stringRedisTemplate.execute(RELEASE_COOLDOWN_SCRIPT, List.of(cooldownKey(phoneHash)), cooldownToken);
    }

    @Override
    public long incrementFailure(String phoneHash, Duration ttl) {
        String key = failureKey(phoneHash);
        Long failureCount = stringRedisTemplate.opsForValue().increment(key);
        // 첫 실패 시점에만 TTL을 부여해 code TTL과 같은 창 안에서 실패 횟수를 누적한다.
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
