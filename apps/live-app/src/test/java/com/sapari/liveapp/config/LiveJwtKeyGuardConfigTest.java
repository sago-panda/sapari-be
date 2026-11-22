package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtTestKeys;

@DisplayName("live-app 은 사용자 토큰을 만들 수 있는 키를 갖고 뜨지 않는다")
class LiveJwtKeyGuardConfigTest {

    private static final KeyPair USER_TOKEN_KEYS = JwtTestKeys.keyPair();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LiveJwtKeyGuardConfig.class);

    @Test
    @DisplayName("공개키만 있고 룸 토큰 키쌍이 따로면 뜬다")
    void verifyOnlyWithSeparateRoomKeyBoots() {
        run(verifyOnly(), JwtTestKeys.generateKeyPair().getPrivate(), true);
    }

    @Test
    @DisplayName("jwt.signing-key 가 있으면 기동하지 않는다")
    void signingKeyIsRejected() {
        run(JwtTestKeys.properties("sapari", 900L, 1_209_600L), JwtTestKeys.generateKeyPair().getPrivate(), false);
    }

    @Test
    @DisplayName("룸 토큰 개인키가 사용자 토큰 키쌍과 같으면 기동하지 않는다")
    void reusedKeyPairIsRejected() {
        run(verifyOnly(), USER_TOKEN_KEYS.getPrivate(), false);
    }

    private void run(JwtProperties properties, PrivateKey roomTokenPrivateKey, boolean expectStarted) {
        runner.withBean(JwtProperties.class, () -> properties)
                .withBean("roomTokenPrivateKey", PrivateKey.class, () -> roomTokenPrivateKey)
                .run(context -> {
                    if (expectStarted) {
                        assertThat(context).hasNotFailed();
                        assertThat(context).hasSingleBean(LiveJwtKeyGuardConfig.LiveJwtKeyGuard.class);
                    } else {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure()).rootCause()
                                .isInstanceOf(IllegalStateException.class)
                                .message().doesNotContain(JwtTestKeys.privateKeyBase64().substring(0, 64));
                    }
                });
    }

    private JwtProperties verifyOnly() {
        return new JwtProperties(
                "sapari",
                null,
                List.of(new JwtProperties.VerificationKey(JwtTestKeys.KID, JwtTestKeys.publicKeyBase64())),
                900L,
                1_209_600L);
    }
}
