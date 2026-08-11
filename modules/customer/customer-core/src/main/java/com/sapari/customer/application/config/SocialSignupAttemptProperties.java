package com.sapari.customer.application.config;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 소셜 회원가입 SID별 시도 제한과 processing lock 정책을 구성한다.
 * 값은 실행 애플리케이션 설정에서 반드시 제공한다.
 * 운영 오설정이 보호 기능을 사실상 끄거나 Redis TTL 범위를 과도하게 키우지 않도록 시도 횟수 1~20회,
 * window 1분~24시간, lock TTL 5초~10분으로 제한하고 lock TTL은 window보다 짧게 유지한다.
 */
@Validated
@ConfigurationProperties(prefix = "sapari.customer.social-signup.attempt")
public record SocialSignupAttemptProperties(
        @Min(1) @Max(20) int maxAttempts,
        @NotNull Duration window,
        @NotNull Duration lockTtl
) {

    private static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    private static final Duration MAX_WINDOW = Duration.ofHours(24);
    private static final Duration MIN_LOCK_TTL = Duration.ofSeconds(5);
    private static final Duration MAX_LOCK_TTL = Duration.ofMinutes(10);

    public SocialSignupAttemptProperties {
        if (maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("maxAttempts는 1 이상 20 이하여야 합니다.");
        }
        requireRange(window, MIN_WINDOW, MAX_WINDOW, "window");
        requireRange(lockTtl, MIN_LOCK_TTL, MAX_LOCK_TTL, "lockTtl");
        if (lockTtl.compareTo(window) >= 0) {
            throw new IllegalArgumentException("lockTtl은 window보다 짧아야 합니다.");
        }
    }

    private static void requireRange(Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + "의 운영 허용 범위를 벗어났습니다.");
        }
    }
}
