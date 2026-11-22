package com.sapari.common.securityjwt.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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

    private static final String PRIVATE_KEY = JwtTestKeys.privateKeyBase64();
    private static final String PUBLIC_KEY = JwtTestKeys.publicKeyBase64();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JwtPropertiesConfig.class);

    @Test
    @DisplayName("toString은 서명 개인키를 마스킹한다")
    void toString_masksPrivateKey() {
        JwtProperties properties = JwtTestKeys.properties("sapari", 900L, 1_209_600L);

        assertThat(properties.toString())
                .doesNotContain(PRIVATE_KEY)
                .contains("privateKey=***");
    }

    @Test
    @DisplayName("서명키 없이 공개키만으로 바인딩된다(검증 전용 앱)")
    void bindsWithPublicKeyOnly() {
        contextRunner.withPropertyValues(
                        "jwt.issuer=sapari",
                        "jwt.verification-keys[0].kid=k1",
                        "jwt.verification-keys[0].public-key=" + PUBLIC_KEY,
                        "jwt.access-token-expiration-seconds=900",
                        "jwt.refresh-token-expiration-seconds=1209600")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    JwtProperties properties = context.getBean(JwtProperties.class);
                    assertThat(properties.signingKey()).isNull();
                    assertThat(properties.verificationKeys()).extracting(JwtProperties.VerificationKey::kid)
                            .containsExactly("k1");
                });
    }

    @Test
    @DisplayName("검증 공개키가 없으면 실패한다")
    void verificationKeysAreRequired() {
        assertThatThrownBy(() -> new JwtProperties("sapari", null, List.of(), 900L, 1_209_600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.verification-keys");
    }

    @Test
    @DisplayName("검증 공개키 kid 가 중복되면 실패한다")
    void duplicateKidIsRejected() {
        List<JwtProperties.VerificationKey> keys = List.of(
                new JwtProperties.VerificationKey("k1", PUBLIC_KEY),
                new JwtProperties.VerificationKey("k1", PUBLIC_KEY));

        assertThatThrownBy(() -> new JwtProperties("sapari", null, keys, 900L, 1_209_600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("서명 kid 가 검증 공개키 목록에 없으면 실패한다")
    void signingKidMustBeVerifiable() {
        assertThatThrownBy(() -> new JwtProperties(
                "sapari",
                new JwtProperties.SigningKey("k2", PRIVATE_KEY),
                List.of(new JwtProperties.VerificationKey("k1", PUBLIC_KEY)),
                900L, 1_209_600L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jwt.signing-key.kid");
    }

    @Test
    @DisplayName("바인딩 검증 실패 예외는 거부된 서명 개인키를 노출하지 않는다")
    void bindingValidationFailure_doesNotExposeRejectedPrivateKey() {
        contextRunner.withPropertyValues(rejectedSigningKeyProperties())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureMessages(context.getStartupFailure()))
                            .doesNotContain(PRIVATE_KEY);
                });
    }

    @Test
    @DisplayName("실제 부팅 실패 진단 로그는 거부된 서명 개인키를 노출하지 않는다")
    void bootFailureAnalysis_doesNotExposeRejectedPrivateKey(CapturedOutput output) {
        SpringApplication application = new SpringApplication(JwtPropertiesConfig.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);

        String[] args = java.util.Arrays.stream(rejectedSigningKeyProperties())
                .map(property -> "--" + property)
                .toArray(String[]::new);
        try {
            application.run(args);
        } catch (RuntimeException ignored) {
            // 실제 Spring Boot FailureAnalyzer가 출력한 진단 블록을 아래에서 검증한다.
        }

        assertThat(output.getAll())
                .contains("APPLICATION FAILED TO START")
                .doesNotContain(PRIVATE_KEY);
    }

    /** 개인키 원문은 정상이고, 서명 kid 가 검증 목록에 없어 거부되는 설정. */
    private String[] rejectedSigningKeyProperties() {
        return new String[] {
                "jwt.issuer=sapari",
                "jwt.signing-key.kid=k2",
                "jwt.signing-key.private-key=" + PRIVATE_KEY,
                "jwt.verification-keys[0].kid=k1",
                "jwt.verification-keys[0].public-key=" + PUBLIC_KEY,
                "jwt.access-token-expiration-seconds=900",
                "jwt.refresh-token-expiration-seconds=1209600"
        };
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
