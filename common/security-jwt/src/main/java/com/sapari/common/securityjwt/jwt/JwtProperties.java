package com.sapari.common.securityjwt.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        String secret,
        @NotNull @Positive Long accessTokenExpirationSeconds,
        @NotNull @Positive Long refreshTokenExpirationSeconds
) {
    public JwtProperties {
        // Bean Validation 의 rejected value 는 바인딩 실패 예외에 포함될 수 있다. secret 은 생성자에서
        // 값 없는 고정 메시지로 검증해 기동 실패 로그에도 HMAC 키 원문을 남기지 않는다.
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalArgumentException("jwt.secret 은 32자 이상이어야 합니다.");
        }
    }

    @Override
    public String toString() {
        return "JwtProperties[issuer=" + issuer
                + ", secret=***"
                + ", accessTokenExpirationSeconds=" + accessTokenExpirationSeconds
                + ", refreshTokenExpirationSeconds=" + refreshTokenExpirationSeconds
                + "]";
    }
}
