package com.sapari.common.securityjwt.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 사용자 JWT(RS256) 설정. 키 원문은 여기서 문자열로만 받고 파싱은 {@link JwtKeys} 가 한다.
 *
 * <p>{@code signingKey} 는 토큰을 발급하는 앱(api-app)에만 둔다 — 검증만 하는 앱(live-app 등)에 개인키가
 * 있으면 그 앱의 비밀만으로 모든 앱에서 통하는 토큰을 위조할 수 있다. {@code verificationKeys} 는 모든 앱에
 * 필수이며, 키 교체 중에는 신·구 공개키를 함께 둔다(토큰 헤더의 kid 로 고른다).
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        SigningKey signingKey,
        List<VerificationKey> verificationKeys,
        @NotNull @Positive Long accessTokenExpirationSeconds,
        @NotNull @Positive Long refreshTokenExpirationSeconds
) {
    public JwtProperties {
        // Bean Validation 의 rejected value 는 바인딩 실패 예외에 포함될 수 있다. 키 항목은 생성자에서
        // 값 없는 고정 메시지로 검증해 기동 실패 로그에도 개인키 원문을 남기지 않는다.
        if (verificationKeys == null || verificationKeys.isEmpty()) {
            throw new IllegalArgumentException("jwt.verification-keys 는 1개 이상이어야 합니다.");
        }
        Set<String> kids = new HashSet<>();
        for (VerificationKey key : verificationKeys) {
            if (key == null || isBlank(key.kid()) || isBlank(key.publicKey())) {
                throw new IllegalArgumentException("jwt.verification-keys 의 각 항목은 kid 와 public-key 가 필요합니다.");
            }
            if (!kids.add(key.kid())) {
                throw new IllegalArgumentException("jwt.verification-keys 의 kid 가 중복됩니다: " + key.kid());
            }
        }
        verificationKeys = List.copyOf(verificationKeys);

        if (signingKey != null) {
            if (isBlank(signingKey.kid()) || isBlank(signingKey.privateKey())) {
                throw new IllegalArgumentException("jwt.signing-key 는 kid 와 private-key 가 모두 필요합니다.");
            }
            // 자기가 발급한 토큰을 자기도 검증하므로(refresh 회전 등) 서명 kid 의 공개키가 반드시 있어야 한다.
            if (!kids.contains(signingKey.kid())) {
                throw new IllegalArgumentException(
                        "jwt.signing-key.kid 가 jwt.verification-keys 에 없습니다: " + signingKey.kid());
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer
                + ", signingKey=" + signingKey
                + ", verificationKeys=" + verificationKeys
                + ", accessTokenExpirationSeconds=" + accessTokenExpirationSeconds
                + ", refreshTokenExpirationSeconds=" + refreshTokenExpirationSeconds
                + "]";
    }

    /**
     * 서명용 RSA 개인키. {@code privateKey} 는 PKCS#8 DER 의 Base64(PEM 헤더 제외).
     */
    public record SigningKey(String kid, String privateKey) {
        @Override
        public String toString() {
            return "SigningKey[kid=" + kid + ", privateKey=***]";
        }
    }

    /**
     * 검증용 RSA 공개키. {@code publicKey} 는 X.509 SubjectPublicKeyInfo DER 의 Base64(PEM 헤더 제외).
     */
    public record VerificationKey(String kid, String publicKey) {
        @Override
        public String toString() {
            return "VerificationKey[kid=" + kid + "]";
        }
    }
}
