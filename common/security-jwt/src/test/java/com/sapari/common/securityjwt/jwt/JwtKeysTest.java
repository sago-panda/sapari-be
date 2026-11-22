package com.sapari.common.securityjwt.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JWT 키 파싱 테스트")
class JwtKeysTest {

    private static final KeyPair KEY_PAIR = JwtTestKeys.generateKeyPair();

    @Test
    @DisplayName("서명 개인키가 같은 kid 공개키와 짝이 아니면 기동 단계에서 실패한다")
    void mismatchedKeyPairIsRejected() {
        KeyPair other = JwtTestKeys.generateKeyPair();

        assertThatThrownBy(() -> JwtKeys.from(properties(
                JwtTestKeys.privateKeyBase64(KEY_PAIR), JwtTestKeys.publicKeyBase64(other))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("짝이 아닙니다");
    }

    @Test
    @DisplayName("2048비트 미만 RSA 키는 기동 단계에서 실패한다")
    void weakKeyIsRejected() {
        KeyPair weak = JwtTestKeys.generateKeyPair(1024);

        assertThatThrownBy(() -> JwtKeys.from(properties(
                JwtTestKeys.privateKeyBase64(weak), JwtTestKeys.publicKeyBase64(weak))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    @DisplayName("개인키 파싱 실패 예외는 키 원문을 싣지 않는다")
    void privateKeyParseFailure_doesNotExposeKey() {
        String broken = JwtTestKeys.privateKeyBase64(KEY_PAIR).substring(0, 400);

        assertThatThrownBy(() -> JwtKeys.from(properties(broken, JwtTestKeys.publicKeyBase64(KEY_PAIR))))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(broken.substring(0, 64));
                    }
                });
    }

    @Test
    @DisplayName("공개키 파싱 실패는 기동 단계에서 kid 와 함께 실패하고 키 원문을 싣지 않는다")
    void publicKeyParseFailureIsRejected() {
        String notAKey = JwtTestKeys.publicKeyBase64(KEY_PAIR).substring(0, 200);
        JwtProperties properties = new JwtProperties(
                "sapari", null, List.of(new JwtProperties.VerificationKey("k1", notAKey)), 900L, 1_209_600L);

        assertThatThrownBy(() -> JwtKeys.from(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kid=k1")
                .satisfies(e -> {
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        assertThat(String.valueOf(t.getMessage())).doesNotContain(notAKey.substring(0, 64));
                    }
                });
    }

    @Test
    @DisplayName("다른 용도 개인키가 검증 공개키와 같은 키쌍인지 가린다")
    void detectsReusedKeyPair() {
        JwtKeys keys = JwtKeys.from(properties(
                JwtTestKeys.privateKeyBase64(KEY_PAIR), JwtTestKeys.publicKeyBase64(KEY_PAIR)));

        assertThat(keys.hasVerificationKeyFor(KEY_PAIR.getPrivate())).isTrue();
        assertThat(keys.hasVerificationKeyFor(JwtTestKeys.generateKeyPair().getPrivate())).isFalse();
    }

    @Test
    @DisplayName("PEM 본문처럼 줄바꿈된 Base64 키도 받는다")
    void acceptsLineWrappedBase64() {
        String wrappedPrivate = wrap(JwtTestKeys.privateKeyBase64(KEY_PAIR));
        String wrappedPublic = wrap(JwtTestKeys.publicKeyBase64(KEY_PAIR));

        assertThat(JwtKeys.from(properties(wrappedPrivate, wrappedPublic))).isNotNull();
    }

    private JwtProperties properties(String privateKey, String publicKey) {
        return new JwtProperties(
                "sapari",
                new JwtProperties.SigningKey("k1", privateKey),
                List.of(new JwtProperties.VerificationKey("k1", publicKey)),
                900L,
                1_209_600L);
    }

    private String wrap(String base64) {
        return String.join("\n", base64.split("(?<=\\G.{64})"));
    }
}
