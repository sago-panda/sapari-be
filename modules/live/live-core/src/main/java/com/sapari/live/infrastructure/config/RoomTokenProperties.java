package com.sapari.live.infrastructure.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 채팅 입장용 룸 토큰(RS256) 발급 설정.
 *
 * <p>api-app 인증 토큰(HMAC 대칭키, {@code JwtProperties})과 <b>별개</b>다. 룸 토큰은 live가
 * <b>개인키로 서명</b>하고 chat이 공개키로 검증만 하는 비대칭(RS256) 모델이라, 여기서는 <b>개인키만</b>
 * 주입받는다(공개키는 chat 측 설정).
 *
 * <p>{@code privateKey}는 PKCS#8 DER를 Base64로 인코딩한 문자열(PEM 헤더/개행 제외). 자격증명이므로
 * env/secret으로 주입하며 로그에 남기지 않는다. {@code issuer}/{@code audience}는 토큰 혼동 차단용
 * 고정값, {@code expirationSeconds}는 짧게(60~120s) 둬 노출 창을 최소화한다.
 *
 * <p>{@code expirationSeconds}의 <b>상한 300초는 chat 과의 계약</b>이다(SPR-185). 넘으면 기동하지 않는다.
 * chat 은 탈퇴 기록처럼 "토큰 수명 이상 남아야 하는" 기록의 TTL 을 이 수에 맞춰 정한다 — 배포 값을 알
 * 수 없으니 상한이 그쪽이 믿을 수 있는 유일한 수다. 올리려면 chat 쪽 TTL 을 먼저 확인할 것.
 *
 * <p>주의: 짧은 TTL은 노출 창을 줄일 뿐 <b>단회성을 강제하지 않는다</b>(jti/nonce 없음 → TTL 창 안에서는
 * 토큰 복제·재사용 가능). 진짜 1회성이 필요하면 jti 발급 + 소비 측(chat) SETNX 마킹이 추가로 필요하다.
 */
@Validated
@ConfigurationProperties("live.room-token")
public record RoomTokenProperties(
        @NotBlank String issuer,
        @NotBlank String audience,
        @NotBlank String privateKey,
        @NotNull @Positive @Max(MAX_EXPIRATION_SECONDS) Long expirationSeconds
) {
    /** 룸 토큰 수명 상한(초) — 위 javadoc 의 chat 계약. */
    public static final long MAX_EXPIRATION_SECONDS = 300L;

    /**
     * 개인키(자격증명)가 로그/예외 메시지로 유출되지 않도록 마스킹한다. record 기본 toString()은
     * privateKey 원문을 그대로 노출하므로 반드시 오버라이드해야 한다(로그 1줄로 RS256 키 유출 방지).
     */
    @Override
    public String toString() {
        return "RoomTokenProperties[issuer=" + issuer
                + ", audience=" + audience
                + ", privateKey=***"
                + ", expirationSeconds=" + expirationSeconds + "]";
    }
}
