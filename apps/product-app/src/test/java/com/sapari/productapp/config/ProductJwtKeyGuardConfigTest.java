package com.sapari.productapp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtTestKeys;

@DisplayName("product-app 은 사용자 토큰 서명 개인키를 갖고 뜨지 않는다")
class ProductJwtKeyGuardConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProductJwtKeyGuardConfig.class);

    @Test
    @DisplayName("공개키만 있으면 뜬다")
    void verifyOnlyBoots() {
        JwtProperties verifyOnly = new JwtProperties(
                "sapari",
                null,
                List.of(new JwtProperties.VerificationKey(JwtTestKeys.KID, JwtTestKeys.publicKeyBase64())),
                900L,
                1_209_600L);

        runner.withBean(JwtProperties.class, () -> verifyOnly)
                .run(context -> assertThat(context)
                        .hasSingleBean(ProductJwtKeyGuardConfig.ProductJwtKeyGuard.class));
    }

    @Test
    @DisplayName("jwt.signing-key 가 있으면 기동하지 않고, 메시지에 개인키 원문이 없다")
    void signingKeyIsRejected() {
        runner.withBean(JwtProperties.class, () -> JwtTestKeys.properties("sapari", 900L, 1_209_600L))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .message().doesNotContain(JwtTestKeys.privateKeyBase64().substring(0, 64));
                });
    }
}
