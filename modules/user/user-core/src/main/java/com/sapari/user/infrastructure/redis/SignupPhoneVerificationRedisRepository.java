package com.sapari.user.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;

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
    private static final Long VERIFIED = 0L;
    private static final Long CODE_NOT_FOUND = 1L;
    private static final Long CODE_MISMATCH = 2L;
    private static final Long ATTEMPTS_EXCEEDED = 3L;

    /**
     * 인증번호 확인은 code 조회, 실패 횟수 증가, code 폐기, verified 저장이 하나의 정책 전이다.
     * 여러 Redis 명령으로 나누면 병렬 confirm 요청에서 성공/실패/초과 처리 순서가 흔들리므로 Lua로 원자화한다.
     * result: 0=인증 성공, 1=code 없음/만료, 2=code 불일치, 3=실패 횟수 초과로 code 폐기.
     */
    private static final DefaultRedisScript<Long> CONFIRM_CODE_SCRIPT = new DefaultRedisScript<>(
            """
            local storedCodeHash = redis.call('get', KEYS[1])
            local requestedCodeHash = ARGV[1]
            local verifiedValue = ARGV[2]
            local codeTtlSeconds = tonumber(ARGV[3])
            local verifiedTtlSeconds = tonumber(ARGV[4])
            local maxAttempts = tonumber(ARGV[5])

            if storedCodeHash == false then
                return 1
            end

            if storedCodeHash == requestedCodeHash then
                redis.call('del', KEYS[1], KEYS[2])
                redis.call('set', KEYS[3], verifiedValue, 'EX', verifiedTtlSeconds)
                return 0
            end

            local failedAttempts = redis.call('incr', KEYS[2])
            if failedAttempts == 1 then
                redis.call('expire', KEYS[2], codeTtlSeconds)
            end

            if failedAttempts >= maxAttempts then
                redis.call('del', KEYS[1], KEYS[2])
                return 3
            end

            return 2
            """,
            Long.class
    );

    /**
     * 발송 실패 보상 시 cooldownToken이 일치하는 key만 삭제한다.
     * GET 후 DEL을 분리하면 뒤따른 재요청의 쿨다운을 지울 수 있어 Lua로 비교·삭제를 원자화한다.
     * result: 1=현재 요청의 cooldown 삭제, 0=token 불일치/이미 없음으로 삭제하지 않음.
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
    public ConfirmResult confirmCode(String phoneHash, String requestedCodeHash, Duration codeTtl, Duration verifiedTtl, int maxAttempts) {
        Long result = stringRedisTemplate.execute(
                CONFIRM_CODE_SCRIPT,
                List.of(codeKey(phoneHash), failureKey(phoneHash), verifiedKey(phoneHash)),
                requestedCodeHash,
                VERIFIED_VALUE,
                String.valueOf(codeTtl.toSeconds()),
                String.valueOf(verifiedTtl.toSeconds()),
                String.valueOf(maxAttempts)
        );

        if (VERIFIED.equals(result)) {
            return ConfirmResult.VERIFIED;
        }
        if (CODE_NOT_FOUND.equals(result)) {
            return ConfirmResult.CODE_NOT_FOUND;
        }
        if (CODE_MISMATCH.equals(result)) {
            return ConfirmResult.CODE_MISMATCH;
        }
        if (ATTEMPTS_EXCEEDED.equals(result)) {
            return ConfirmResult.ATTEMPTS_EXCEEDED;
        }
        // Redis script와 Java enum 매핑 계약이 어긋난 경우 사용자 입력 오류로 숨기지 않는다.
        throw new IllegalStateException("Unexpected signup phone verification confirm result: " + result);
    }

    @Override
    public void saveCode(String phoneHash, String codeHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(codeKey(phoneHash), codeHash, ttl);
    }

    @Override
    public void deleteFailures(String phoneHash) {
        stringRedisTemplate.delete(failureKey(phoneHash));
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
