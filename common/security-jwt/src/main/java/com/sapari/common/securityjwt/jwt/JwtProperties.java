package com.sapari.common.securityjwt.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * [SPR-144 로 이월] {@code secret} 에 {@code toString()} 마스킹이 없다 — record 기본 구현이 원문을
 * 그대로 내보내므로 바인딩 실패 로그 한 번에 HMAC 키가 평문으로 남고, 그 키면 임의 사용자 토큰을
 * 위조할 수 있다. 형제인 {@code LiveKitProperties}·{@code RoomTokenProperties} 는 같은 이유로 이미
 * 마스킹한다. SPR-142(정리 스케줄러 분산 락)와 무관한 기존 문제라 별도 티켓에서 닫는다.
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String issuer,
        @NotBlank @Size(min = 32) String secret,
        @NotNull @Positive Long accessTokenExpirationSeconds,
        @NotNull @Positive Long refreshTokenExpirationSeconds
) {
}
