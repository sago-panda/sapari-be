package com.sapari.common.securityjwt.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;

@ExtendWith(OutputCaptureExtension.class)
class JwtPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtPropertiesConfig.class);

    @Test
    @DisplayName("toString은 JWT 비밀키를 마스킹한다")
    void toString_masksSecret() {
        String secret = "01234567890123456789012345678901";
        JwtProperties properties = new JwtProperties("sapari", secret, 900L, 1_209_600L);

        assertThat(properties.toString())
                .doesNotContain(secret)
                .contains("secret=***");
    }

    @Test
    @DisplayName("바인딩 검증 실패 예외는 거부된 JWT 비밀키를 노출하지 않는다")
    void bindingValidationFailure_doesNotExposeRejectedSecret() {
        String rejectedSecret = "short-secret";

        contextRunner.withPropertyValues(
                        "jwt.issuer=sapari",
                        "jwt.secret=" + rejectedSecret,
                        "jwt.access-token-expiration-seconds=900",
                        "jwt.refresh-token-expiration-seconds=1209600")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain(rejectedSecret);
                });
    }

    @Test
    @DisplayName("실제 부팅 실패 진단 로그는 거부된 JWT 비밀키를 노출하지 않는다")
    void bootFailureAnalysis_doesNotExposeRejectedSecret(CapturedOutput output) {
        String rejectedSecret = "short-secret";
        SpringApplication application = new SpringApplication(JwtPropertiesConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);

        try {
            application.run(
                    "--jwt.issuer=sapari",
                    "--jwt.secret=" + rejectedSecret,
                    "--jwt.access-token-expiration-seconds=900",
                    "--jwt.refresh-token-expiration-seconds=1209600");
        } catch (RuntimeException ignored) {
            // 실제 Spring Boot FailureAnalyzer가 출력한 진단 블록을 아래에서 검증한다.
        }

        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .doesNotContain(rejectedSecret);
    }

    private String failureMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            messages.append(current).append('\n');
            for (Throwable suppressed : current.getSuppressed()) {
                messages.append(suppressed).append('\n');
            }
        }
        return messages.toString();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfig {
    }
}
