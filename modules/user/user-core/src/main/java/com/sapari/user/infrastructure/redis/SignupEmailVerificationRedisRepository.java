package com.sapari.user.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.user.domain.repository.SignupEmailVerificationRepository;

/**
 * 회원가입 이메일 인증 상태를 Redis에 저장한다.
 * 이메일 원문 대신 HMAC emailHash를 key suffix로 사용하고, codeHash만 value에 저장한다.
 */
@Repository
@RequiredArgsConstructor
public class SignupEmailVerificationRedisRepository implements SignupEmailVerificationRepository {

    private static final String SIGN_UP_NAMESPACE = "SIGN_UP";
    private static final String CODE_KEY_PREFIX = "email:code";
    private static final String VERIFIED_KEY_PREFIX = "email:verified";
    private static final String FAILURE_KEY_PREFIX = "email:fail";
    private static final String COOLDOWN_KEY_PREFIX = "email:cooldown";
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
     * KEYS[1]은 cooldown key, ARGV[1]은 현재 요청이 선점한 token이며 일치할 때만 삭제한다.
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
    public ConfirmResult confirmCode(String emailHash, String requestedCodeHash, Duration codeTtl, Duration verifiedTtl, int maxAttempts) {
        Long result = stringRedisTemplate.execute(
                CONFIRM_CODE_SCRIPT,
                List.of(codeKey(emailHash), failureKey(emailHash), verifiedKey(emailHash)),
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
        throw new IllegalStateException("Unexpected signup email verification confirm result: " + result);
    }

    /**
     * 발송 성공 후에만 이메일별 codeHash를 TTL과 함께 저장한다.
     */
    @Override
    public void saveCode(String emailHash, String codeHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(codeKey(emailHash), codeHash, ttl);
    }

    /**
     * 새 인증번호가 정상 발송되면 이전 code의 실패 횟수만 초기화한다.
     */
    @Override
    public void deleteFailures(String emailHash) {
        stringRedisTemplate.delete(failureKey(emailHash));
    }

    /**
     * Redis GETDEL로 인증 완료 상태를 읽는 동시에 삭제한다.
     * 같은 verified key가 구매자/판매자 가입 재시도나 병렬 요청에서 두 번 사용되지 않도록 한다.
     */
    @Override
    public boolean consumeVerified(String emailHash) {
        return VERIFIED_VALUE.equals(stringRedisTemplate.opsForValue().getAndDelete(verifiedKey(emailHash)));
    }

    /**
     * 이메일 발송 전 cooldown key를 선점해 짧은 시간 안의 재발송과 provider 비용 증가를 막는다.
     */
    @Override
    public boolean acquireCooldown(String emailHash, String cooldownToken, Duration ttl) {
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(cooldownKey(emailHash), cooldownToken, ttl);
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 발송 실패 시 현재 요청이 선점한 cooldown만 해제하도록 Lua script에 token을 전달한다.
     */
    @Override
    public void releaseCooldown(String emailHash, String cooldownToken) {
        stringRedisTemplate.execute(RELEASE_COOLDOWN_SCRIPT, List.of(cooldownKey(emailHash)), cooldownToken);
    }

    /**
     * 이메일별 인증번호 hash 저장 key를 만든다.
     */
    private String codeKey(String emailHash) { return key(CODE_KEY_PREFIX, emailHash); }

    /**
     * 최종 가입 단계에서 소비할 이메일 인증 완료 key를 만든다.
     */
    private String verifiedKey(String emailHash) { return key(VERIFIED_KEY_PREFIX, emailHash); }

    /**
     * 이메일 인증번호 불일치 실패 횟수 key를 만든다.
     */
    private String failureKey(String emailHash) { return key(FAILURE_KEY_PREFIX, emailHash); }

    /**
     * 이메일 재발송 제한 key를 만든다.
     */
    private String cooldownKey(String emailHash) { return key(COOLDOWN_KEY_PREFIX, emailHash); }

    /**
     * 이메일 원문 대신 HMAC emailHash를 suffix로 사용해 Redis key에 PII가 남지 않게 한다.
     */
    private String key(String prefix, String emailHash) {
        return prefix + ":" + SIGN_UP_NAMESPACE + ":" + emailHash;
    }
}
