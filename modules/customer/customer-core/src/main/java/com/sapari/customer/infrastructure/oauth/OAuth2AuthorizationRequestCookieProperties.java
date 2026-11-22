package com.sapari.customer.infrastructure.oauth;

import java.util.Base64;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2 인가 요청 쿠키를 암호화하는 AES-256 키 설정이다. 모든 api-app Pod 가 같은 값을 써야
 * 로그인 시작과 콜백이 다른 Pod 로 가도 복호화된다.
 * 키 원문이 기동 실패 로그에 남지 않도록 값 없는 고정 메시지로 검증한다.
 */
@ConfigurationProperties(prefix = "customer.oauth2.authorization-request-cookie")
public record OAuth2AuthorizationRequestCookieProperties(
        String encryptionKey
) {

    private static final int AES_256_KEY_BYTES = 32;

    public OAuth2AuthorizationRequestCookieProperties {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            throw new IllegalArgumentException("customer.oauth2.authorization-request-cookie.encryption-key 는 필수입니다.");
        }
        if (decodeKey(encryptionKey).length != AES_256_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "customer.oauth2.authorization-request-cookie.encryption-key 는 32바이트를 Base64 로 인코딩한 값이어야 합니다."
            );
        }
    }

    public byte[] encryptionKeyBytes() {
        return decodeKey(encryptionKey);
    }

    private static byte[] decodeKey(String encodedKey) {
        try {
            return Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "customer.oauth2.authorization-request-cookie.encryption-key 는 Base64 형식이어야 합니다."
            );
        }
    }

    @Override
    public String toString() {
        return "OAuth2AuthorizationRequestCookieProperties[encryptionKey=***]";
    }
}
