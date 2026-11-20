package com.sapari.customer.application.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.sapari.customer.domain.repository.SocialSignupAttemptRepository;
import com.sapari.customer.infrastructure.redis.SocialSignupAttemptRedisRepository;

@DisplayName("소셜 회원가입 SID 시도 제어 설정 테스트")
class SocialSignupAttemptConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    SocialSignupAttemptConfiguration.class,
                    SocialSignupAttemptRedisRepository.class
            )
            .withPropertyValues(
                    "sapari.customer.social-signup.attempt.max-attempts=5",
                    "sapari.customer.social-signup.attempt.window=30m",
                    "sapari.customer.social-signup.attempt.lock-ttl=2m"
            )
            .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class));

    @Test
    @DisplayName("application 설정은 properties와 Redis repository bean을 등록한다")
    void registersPropertiesAndRepositoryBeansFromConfiguredValues() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SocialSignupAttemptProperties.class);
            assertThat(context).hasSingleBean(SocialSignupAttemptRepository.class);
            SocialSignupAttemptProperties properties = context.getBean(SocialSignupAttemptProperties.class);
            assertThat(properties.maxAttempts()).isEqualTo(5);
            assertThat(properties.window()).isEqualTo(Duration.ofMinutes(30));
            assertThat(properties.lockTtl()).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Test
    @DisplayName("시도 제한 설정이 없으면 기동에 실패한다")
    void failsWhenAttemptPolicyIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        SocialSignupAttemptConfiguration.class,
                        SocialSignupAttemptRedisRepository.class
                )
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("kebab-case 설정으로 시도 횟수와 TTL 정책을 재정의한다")
    void bindsKebabCaseOverrides() {
        contextRunner
                .withPropertyValues(
                        "sapari.customer.social-signup.attempt.max-attempts=7",
                        "sapari.customer.social-signup.attempt.window=45m",
                        "sapari.customer.social-signup.attempt.lock-ttl=90s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SocialSignupAttemptProperties properties = context.getBean(SocialSignupAttemptProperties.class);
                    assertThat(properties.maxAttempts()).isEqualTo(7);
                    assertThat(properties.window()).isEqualTo(Duration.ofMinutes(45));
                    assertThat(properties.lockTtl()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    @Test
    @DisplayName("운영 정책보다 큰 최대 시도 횟수는 기동 시 거부한다")
    void rejectsMaxAttemptsAboveOperationalPolicy() {
        contextRunner
                .withPropertyValues("sapari.customer.social-signup.attempt.max-attempts=21")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("운영 최소값보다 짧은 시도 window는 기동 시 거부한다")
    void rejectsWindowBelowOperationalPolicy() {
        contextRunner
                .withPropertyValues("sapari.customer.social-signup.attempt.window=59s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("운영 최대값보다 긴 lock TTL은 기동 시 거부한다")
    void rejectsLockTtlAboveOperationalPolicy() {
        contextRunner
                .withPropertyValues("sapari.customer.social-signup.attempt.lock-ttl=11m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("lock TTL이 시도 window 이상이면 기동 시 거부한다")
    void rejectsLockTtlNotShorterThanWindow() {
        contextRunner
                .withPropertyValues(
                        "sapari.customer.social-signup.attempt.window=5m",
                        "sapari.customer.social-signup.attempt.lock-ttl=5m"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("Redis template이 없으면 repository 의존성 누락으로 기동에 실패한다")
    void failsWhenRedisTemplateDependencyIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        SocialSignupAttemptConfiguration.class,
                        SocialSignupAttemptRedisRepository.class
                )
                .run(context -> assertThat(context).hasFailed());
    }
}
