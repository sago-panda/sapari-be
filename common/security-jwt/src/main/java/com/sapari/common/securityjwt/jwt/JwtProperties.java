package com.sapari.common.securityjwt.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String secret,
        @NotNull @Positive Long accessTokenExpirationSeconds,
        @NotNull @Positive Long refreshTokenExpirationSeconds
) {
}
