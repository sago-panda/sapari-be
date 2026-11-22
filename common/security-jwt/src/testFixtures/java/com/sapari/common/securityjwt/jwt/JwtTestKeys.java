package com.sapari.common.securityjwt.jwt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * 테스트용 RS256 키. 실행마다 키쌍을 새로 만들므로 저장소에 키가 남지 않는다.
 */
public final class JwtTestKeys {

    public static final String KID = "test-key";

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private JwtTestKeys() {
    }

    /** 서명키와 그 공개키를 모두 가진(발급 앱용) 설정. */
    public static JwtProperties properties(String issuer, long accessTokenSeconds, long refreshTokenSeconds) {
        return new JwtProperties(
                issuer,
                new JwtProperties.SigningKey(KID, privateKeyBase64()),
                List.of(new JwtProperties.VerificationKey(KID, publicKeyBase64())),
                accessTokenSeconds,
                refreshTokenSeconds
        );
    }

    /** {@link #properties} 가 쓰는 키쌍. */
    public static KeyPair keyPair() {
        return KEY_PAIR;
    }

    public static String privateKeyBase64() {
        return privateKeyBase64(KEY_PAIR);
    }

    public static String publicKeyBase64() {
        return publicKeyBase64(KEY_PAIR);
    }

    public static String privateKeyBase64(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    public static String publicKeyBase64(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public static KeyPair generateKeyPair() {
        return generateKeyPair(2048);
    }

    public static KeyPair generateKeyPair(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA 를 쓸 수 없다", e);
        }
    }
}
