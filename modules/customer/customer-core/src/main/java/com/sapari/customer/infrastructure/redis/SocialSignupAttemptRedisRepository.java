package com.sapari.customer.infrastructure.redis;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.customer.application.config.SocialSignupAttemptProperties;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;
import com.sapari.customer.domain.repository.SocialSignupAttemptRepository;

/** Redis 원자 연산으로 소셜 회원가입 SID의 처리권과 시도 횟수를 제어한다. */
@Repository
public class SocialSignupAttemptRedisRepository implements SocialSignupAttemptRepository {

    private static final long ACQUIRED = 0L;
    private static final long ALREADY_PROCESSING = 1L;
    private static final long RATE_LIMIT_EXCEEDED = 2L;

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then
                return 1
            end
            local attempts = tonumber(redis.call('GET', KEYS[1]) or '0')
            if attempts >= tonumber(ARGV[1]) then
                return 2
            end
            local locked = redis.call('SET', KEYS[2], ARGV[2], 'NX', 'PX', ARGV[3])
            if not locked then
                return 1
            end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[4])
            end
            return 0
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final SocialSignupAttemptProperties properties;

    public SocialSignupAttemptRedisRepository(
            StringRedisTemplate redisTemplate,
            SocialSignupAttemptProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * lock 확인, 한도 확인, lock 생성, count 증가를 하나의 원자 연산으로 수행한다.
     * lock으로 거절된 동시 요청은 quota를 차감하지 않는다.
     */
    @Override
    public AcquireResult tryAcquire(String signupSid) {
        String leaseToken = UUID.randomUUID().toString();
        Long result;
        try {
            result = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    List.of(attemptKey(signupSid), lockKey(signupSid)),
                    Integer.toString(properties.maxAttempts()),
                    leaseToken,
                    Long.toString(properties.lockTtl().toMillis()),
                    Long.toString(properties.window().toMillis())
            );
        } catch (DataAccessException e) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE, e);
        }

        if (result == null) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE);
        }
        if (result == ACQUIRED) {
            return AcquireResult.acquired(leaseToken);
        }
        if (result == ALREADY_PROCESSING) {
            return AcquireResult.alreadyProcessing();
        }
        if (result == RATE_LIMIT_EXCEEDED) {
            return AcquireResult.rateLimitExceeded();
        }
        throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE);
    }

    /** 현재 lock의 lease token이 호출자의 token과 일치할 때만 lock을 삭제한다. */
    @Override
    public void release(String signupSid, String leaseToken) {
        Long result;
        try {
            result = redisTemplate.execute(RELEASE_SCRIPT, List.of(lockKey(signupSid)), leaseToken);
        } catch (DataAccessException e) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE, e);
        }
        if (result == null || (result != 0L && result != 1L)) {
            throw new CustomerException(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE);
        }
    }

    static String attemptKey(String signupSid) {
        return "signup:social:attempt:{" + signupSid + "}";
    }

    static String lockKey(String signupSid) {
        return "signup:social:lock:{" + signupSid + "}";
    }
}
