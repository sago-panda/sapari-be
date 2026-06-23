package com.sapari.customer.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@DisplayName("구매자 휴대폰 인증 Redis repository 테스트")
class CustomerPhoneVerificationRedisRepositoryTest {

    private static final String PHONE_HASH = "phone-hash";
    private static final String COOLDOWN_KEY = "sms:cooldown:SIGN_UP:" + PHONE_HASH;
    private static final String COOLDOWN_TOKEN = "cooldown-token";
    private static final Duration COOLDOWN_TTL = Duration.ofSeconds(60);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Test
    @DisplayName("쿨다운 key 선점에 성공하면 true를 반환한다")
    void acquireCooldownWhenSetIfAbsentSucceedsReturnsTrue() {
        CustomerPhoneVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(COOLDOWN_KEY, COOLDOWN_TOKEN, COOLDOWN_TTL)).thenReturn(true);

        boolean acquired = repository.acquireCooldown(PHONE_HASH, COOLDOWN_TOKEN, COOLDOWN_TTL);

        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("쿨다운 key가 이미 있으면 선점에 실패한다")
    void acquireCooldownWhenSetIfAbsentFailsReturnsFalse() {
        CustomerPhoneVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(COOLDOWN_KEY, COOLDOWN_TOKEN, COOLDOWN_TTL)).thenReturn(false);

        boolean acquired = repository.acquireCooldown(PHONE_HASH, COOLDOWN_TOKEN, COOLDOWN_TTL);

        assertThat(acquired).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("발송 실패 보상 시 내가 선점한 쿨다운 key만 삭제한다")
    void releaseCooldownDeletesCooldownKeyOnlyWhenTokenMatches() {
        CustomerPhoneVerificationRedisRepository repository = repository();

        repository.releaseCooldown(PHONE_HASH, COOLDOWN_TOKEN);

        verify(stringRedisTemplate).execute(
                any(RedisScript.class),
                eq(java.util.List.of(COOLDOWN_KEY)),
                eq(COOLDOWN_TOKEN)
        );
    }

    private CustomerPhoneVerificationRedisRepository repository() {
        return new CustomerPhoneVerificationRedisRepository(stringRedisTemplate);
    }
}
