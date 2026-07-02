package com.sapari.user.application.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

@DisplayName("회원가입 인증 HMAC security properties 테스트")
class UserSignupVerificationSecurityPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("HMAC secret이 있으면 설정 바인딩에 성공한다")
    void bindsWhenHmacSecretExists() {
        contextRunner
                .withPropertyValues("sapari.signup-verification.security.hmac-secret=test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(UserSignupVerificationSecurityProperties.class).getHmacSecret())
                            .isEqualTo("test-secret");
                });
    }

    @Test
    @DisplayName("HMAC secret이 blank면 설정 바인딩에 실패한다")
    void failsWhenHmacSecretBlank() {
        contextRunner
                .withPropertyValues("sapari.signup-verification.security.hmac-secret=   ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UserSignupVerificationSecurityProperties.class)
    static class TestConfig {
    }
}
