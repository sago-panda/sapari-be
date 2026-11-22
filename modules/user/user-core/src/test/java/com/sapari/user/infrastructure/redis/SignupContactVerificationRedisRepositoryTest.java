package com.sapari.user.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.user.domain.repository.SignupContactVerificationRepository.ConsumeResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원가입 연락처 인증 Redis repository 테스트")
class SignupContactVerificationRedisRepositoryTest {

    private static final String PHONE_HASH = "phone-hash";
    private static final String EMAIL_HASH = "email-hash";
    private static final String PHONE_VERIFIED_KEY = "sms:verified:SIGN_UP:" + PHONE_HASH;
    private static final String EMAIL_VERIFIED_KEY = "email:verified:SIGN_UP:" + EMAIL_HASH;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("휴대폰과 이메일 verified가 모두 있으면 Lua script로 두 key를 함께 삭제한다")
    void consumeVerifiedWhenBothExistReturnsConsumed() {
        SignupContactVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(List.of(PHONE_VERIFIED_KEY, EMAIL_VERIFIED_KEY)), eq("true")))
                .thenReturn(0L);

        ConsumeResult result = repository.consumeVerified(PHONE_HASH, EMAIL_HASH);

        assertThat(result).isEqualTo(ConsumeResult.CONSUMED);
        verify(stringRedisTemplate).execute(any(RedisScript.class), eq(List.of(PHONE_VERIFIED_KEY, EMAIL_VERIFIED_KEY)), eq("true"));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("휴대폰 verified가 없으면 아무 key도 삭제하지 않고 휴대폰 누락을 반환한다")
    void consumeVerifiedWhenPhoneMissingReturnsPhoneMissing() {
        SignupContactVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(List.of(PHONE_VERIFIED_KEY, EMAIL_VERIFIED_KEY)), eq("true")))
                .thenReturn(1L);

        ConsumeResult result = repository.consumeVerified(PHONE_HASH, EMAIL_HASH);

        assertThat(result).isEqualTo(ConsumeResult.PHONE_MISSING);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("이메일 verified가 없으면 아무 key도 삭제하지 않고 이메일 누락을 반환한다")
    void consumeVerifiedWhenEmailMissingReturnsEmailMissing() {
        SignupContactVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), eq(List.of(PHONE_VERIFIED_KEY, EMAIL_VERIFIED_KEY)), eq("true")))
                .thenReturn(2L);

        ConsumeResult result = repository.consumeVerified(PHONE_HASH, EMAIL_HASH);

        assertThat(result).isEqualTo(ConsumeResult.EMAIL_MISSING);
    }

    private SignupContactVerificationRedisRepository repository() {
        return new SignupContactVerificationRedisRepository(stringRedisTemplate);
    }
}
