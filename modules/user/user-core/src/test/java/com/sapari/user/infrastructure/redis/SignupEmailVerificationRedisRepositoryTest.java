package com.sapari.user.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.sapari.user.domain.repository.SignupEmailVerificationRepository.ConfirmResult;

@ExtendWith(MockitoExtension.class)
@DisplayName("회원가입 이메일 인증 Redis repository 테스트")
class SignupEmailVerificationRedisRepositoryTest {

    private static final String EMAIL_HASH = "email-hash";
    private static final String CODE_KEY = "email:code:SIGN_UP:" + EMAIL_HASH;
    private static final String FAILURE_KEY = "email:fail:SIGN_UP:" + EMAIL_HASH;
    private static final String VERIFIED_KEY = "email:verified:SIGN_UP:" + EMAIL_HASH;
    private static final String CODE_HASH = "code-hash";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration VERIFIED_TTL = Duration.ofMinutes(30);

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("인증번호 confirm script가 성공을 반환하면 verified 결과로 매핑한다")
    void confirmCodeWhenScriptReturnsVerifiedReturnsVerified() {
        SignupEmailVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(
                any(RedisScript.class),
                eq(List.of(CODE_KEY, FAILURE_KEY, VERIFIED_KEY)),
                eq(CODE_HASH),
                eq("true"),
                eq("300"),
                eq("1800"),
                eq("5")
        )).thenReturn(0L);

        ConfirmResult result = repository.confirmCode(EMAIL_HASH, CODE_HASH, CODE_TTL, VERIFIED_TTL, 5);

        assertThat(result).isEqualTo(ConfirmResult.VERIFIED);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("인증번호 confirm script가 code 없음을 반환하면 code 없음 결과로 매핑한다")
    void confirmCodeWhenScriptReturnsCodeNotFoundReturnsCodeNotFound() {
        SignupEmailVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), eq(CODE_HASH), eq("true"), eq("300"), eq("1800"), eq("5")))
                .thenReturn(1L);

        ConfirmResult result = repository.confirmCode(EMAIL_HASH, CODE_HASH, CODE_TTL, VERIFIED_TTL, 5);

        assertThat(result).isEqualTo(ConfirmResult.CODE_NOT_FOUND);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("인증번호 confirm script가 불일치를 반환하면 불일치 결과로 매핑한다")
    void confirmCodeWhenScriptReturnsMismatchReturnsMismatch() {
        SignupEmailVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), eq(CODE_HASH), eq("true"), eq("300"), eq("1800"), eq("5")))
                .thenReturn(2L);

        ConfirmResult result = repository.confirmCode(EMAIL_HASH, CODE_HASH, CODE_TTL, VERIFIED_TTL, 5);

        assertThat(result).isEqualTo(ConfirmResult.CODE_MISMATCH);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("인증번호 confirm script가 시도 초과를 반환하면 시도 초과 결과로 매핑한다")
    void confirmCodeWhenScriptReturnsAttemptsExceededReturnsAttemptsExceeded() {
        SignupEmailVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), eq(CODE_HASH), eq("true"), eq("300"), eq("1800"), eq("5")))
                .thenReturn(3L);

        ConfirmResult result = repository.confirmCode(EMAIL_HASH, CODE_HASH, CODE_TTL, VERIFIED_TTL, 5);

        assertThat(result).isEqualTo(ConfirmResult.ATTEMPTS_EXCEEDED);
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("인증번호 confirm script가 알 수 없는 값을 반환하면 인프라 오류로 실패한다")
    void confirmCodeWhenScriptReturnsUnknownResultThrowsIllegalStateException() {
        SignupEmailVerificationRedisRepository repository = repository();
        when(stringRedisTemplate.execute(any(RedisScript.class), any(List.class), eq(CODE_HASH), eq("true"), eq("300"), eq("1800"), eq("5")))
                .thenReturn(99L);

        assertThatThrownBy(() -> repository.confirmCode(EMAIL_HASH, CODE_HASH, CODE_TTL, VERIFIED_TTL, 5))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected signup email verification confirm result");
    }

    private SignupEmailVerificationRedisRepository repository() {
        return new SignupEmailVerificationRedisRepository(stringRedisTemplate);
    }
}
