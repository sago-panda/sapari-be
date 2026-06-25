package com.sapari.user.infrastructure.redis;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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

    /**
     * 발송 실패 보상 시 cooldownToken이 일치하는 key만 삭제한다.
     * GET 후 DEL을 분리하면 뒤따른 재요청의 쿨다운을 지울 수 있어 Lua로 비교·삭제를 원자화한다.
     * KEYS[1]은 cooldown key, ARGV[1]은 현재 요청이 선점한 token이며 일치할 때만 DEL 결과를 반환한다.
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
     * 발송 성공 후에만 이메일별 codeHash를 TTL과 함께 저장한다.
     */
    @Override
    public void saveCode(String emailHash, String codeHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(codeKey(emailHash), codeHash, ttl);
    }

    /**
     * 이메일 인증번호 확인 시 원문 code 대신 저장된 codeHash만 조회한다.
     */
    @Override
    public Optional<String> findCodeHash(String emailHash) {
        return Optional.ofNullable(stringRedisTemplate.opsForValue().get(codeKey(emailHash)));
    }

    /**
     * 인증 성공 또는 실패 횟수 초과 시 code/fail 상태를 함께 제거해 이전 code 재사용을 막는다.
     */
    @Override
    public void deleteCodeAndFailures(String emailHash) {
        stringRedisTemplate.delete(List.of(codeKey(emailHash), failureKey(emailHash)));
    }

    /**
     * 새 인증번호가 정상 발송되면 이전 code의 실패 횟수만 초기화한다.
     */
    @Override
    public void deleteFailures(String emailHash) {
        stringRedisTemplate.delete(failureKey(emailHash));
    }

    /**
     * 인증번호 확인 성공 후 최종 가입 API가 소비할 verified 상태를 별도 TTL로 저장한다.
     */
    @Override
    public void saveVerified(String emailHash, Duration ttl) {
        stringRedisTemplate.opsForValue().set(verifiedKey(emailHash), VERIFIED_VALUE, ttl);
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
     * code TTL 창 안에서 이메일별 실패 횟수를 누적해 짧은 숫자 코드 brute-force를 제한한다.
     */
    @Override
    public long incrementFailure(String emailHash, Duration ttl) {
        String key = failureKey(emailHash);
        Long failureCount = stringRedisTemplate.opsForValue().increment(key);
        // 첫 실패 시점에만 TTL을 부여해 code TTL과 같은 창 안에서 실패 횟수를 누적한다.
        if (Long.valueOf(1L).equals(failureCount)) {
            stringRedisTemplate.expire(key, ttl);
        }
        return failureCount == null ? 0L : failureCount;
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
