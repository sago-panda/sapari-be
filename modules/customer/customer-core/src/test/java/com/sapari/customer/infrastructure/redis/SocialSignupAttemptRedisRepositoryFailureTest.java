package com.sapari.customer.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.customer.application.config.SocialSignupAttemptProperties;
import com.sapari.customer.domain.exception.CustomerErrorCode;
import com.sapari.customer.domain.exception.CustomerException;

@DisplayName("소셜 회원가입 SID Redis 시도 제어 장애 테스트")
class SocialSignupAttemptRedisRepositoryFailureTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final SocialSignupAttemptRedisRepository repository = new SocialSignupAttemptRedisRepository(
            redisTemplate,
            new SocialSignupAttemptProperties(5, Duration.ofMinutes(30), Duration.ofMinutes(2))
    );

    @Test
    @DisplayName("처리권 획득 중 Redis 장애를 fail-closed 예외로 변환한다")
    void wrapsRedisFailureWhileAcquiring() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("test Redis unavailable"));

        assertThatThrownBy(() -> repository.tryAcquire("signup-session-id"))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE);
                    assertThat(exception)
                            .hasCauseInstanceOf(RedisConnectionFailureException.class);
                });
    }

    @Test
    @DisplayName("처리권 획득 결과가 null이면 fail-closed 한다")
    void failsClosedWhenAcquireResultIsNull() {
        when(redisTemplate.execute(any(), anyList(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> repository.tryAcquire("signup-session-id"))
                .isInstanceOfSatisfying(CustomerException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE)
                );
    }

    @Test
    @DisplayName("처리권 해제 중 Redis 장애를 cleanup 예외로 변환한다")
    void wrapsRedisFailureWhileReleasing() {
        when(redisTemplate.execute(any(), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("test Redis unavailable"));

        assertThatThrownBy(() -> repository.release("signup-session-id", "lease-token"))
                .isInstanceOfSatisfying(CustomerException.class, exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(CustomerErrorCode.SOCIAL_SIGNUP_ATTEMPT_CONTROL_UNAVAILABLE);
                    assertThat(exception)
                            .hasCauseInstanceOf(RedisConnectionFailureException.class);
                });
    }
}
