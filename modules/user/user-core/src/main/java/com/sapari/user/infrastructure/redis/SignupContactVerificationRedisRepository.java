package com.sapari.user.infrastructure.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.sapari.user.domain.repository.SignupContactVerificationRepository;

/**
 * Redis Lua script로 휴대폰·이메일 verified 상태를 하나의 원자 연산으로 소비한다.
 * 기존 phone/email Redis repository의 개별 consume(GETDEL)을 순서대로 조합하면 한쪽을 먼저 삭제한 뒤
 * 다른 쪽이 만료·누락된 경우 이미 삭제된 인증 상태를 되돌릴 수 없다.
 * 최종 가입에서는 두 인증이 모두 있을 때만 함께 삭제해야 하므로, 단일 Lua script로 check-and-delete를 묶은
 * 전용 repository를 둔다.
 */
@Repository
public class SignupContactVerificationRedisRepository implements SignupContactVerificationRepository {

    private static final String PHONE_VERIFIED_KEY_PREFIX = "sms:verified:SIGN_UP:";
    private static final String EMAIL_VERIFIED_KEY_PREFIX = "email:verified:SIGN_UP:";
    private static final String VERIFIED_VALUE = "true";
    private static final Long CONSUMED = 0L;
    private static final Long PHONE_MISSING = 1L;
    private static final Long EMAIL_MISSING = 2L;

    /**
     * 휴대폰·이메일 verified 상태를 하나의 Redis 원자 연산으로 확인하고 소비한다.
     * KEYS[1]은 phone verified key, KEYS[2]는 email verified key이며 ARGV[1]은 verified value다.
     * 둘 다 verified value와 일치할 때만 두 key를 삭제하고, phone/email 중 누락된 쪽을 return code로 구분한다.
     */
    private static final DefaultRedisScript<Long> CONSUME_VERIFIED_SCRIPT = new DefaultRedisScript<>("""
            local verifiedValue = ARGV[1]
            local phoneVerified = redis.call('get', KEYS[1])
            local emailVerified = redis.call('get', KEYS[2])

            if phoneVerified ~= verifiedValue then
                return 1
            end

            if emailVerified ~= verifiedValue then
                return 2
            end

            redis.call('del', KEYS[1], KEYS[2])
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    public SignupContactVerificationRedisRepository(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 휴대폰·이메일 verified key를 모두 확인한 뒤 둘 다 있을 때만 삭제한다.
     * Lua script 안에서 check와 delete를 함께 수행해 한쪽 실패 시 다른 쪽 인증이 사라지지 않게 한다.
     */
    @Override
    public ConsumeResult consumeVerified(String phoneHash, String emailHash) {
        Long result = stringRedisTemplate.execute(
                CONSUME_VERIFIED_SCRIPT,
                List.of(verifiedPhoneKey(phoneHash), verifiedEmailKey(emailHash)),
                VERIFIED_VALUE
        );

        if (CONSUMED.equals(result)) {
            return ConsumeResult.CONSUMED;
        }
        if (EMAIL_MISSING.equals(result)) {
            return ConsumeResult.EMAIL_MISSING;
        }
        return ConsumeResult.PHONE_MISSING;
    }

    /**
     * 기존 휴대폰 인증 repository가 발급한 verified key와 같은 Redis key를 사용한다.
     */
    private String verifiedPhoneKey(String phoneHash) {
        return PHONE_VERIFIED_KEY_PREFIX + phoneHash;
    }

    /**
     * 기존 이메일 인증 repository가 발급한 verified key와 같은 Redis key를 사용한다.
     */
    private String verifiedEmailKey(String emailHash) {
        return EMAIL_VERIFIED_KEY_PREFIX + emailHash;
    }
}
