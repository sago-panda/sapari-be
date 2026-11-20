package com.sapari.liveapp.security;

import java.util.UUID;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * live-app 인증 주체. access JWT claim(userId·role·nickname·email)과 그 토큰을 가리키는 두
 * 식별자(tokenId=jti·sessionId=sid)를 담아 SecurityContext에 싣는다.
 *
 * <p>{@link AuthenticatedPrincipal#getName()}이 userId를 반환하므로, {@code authentication.getName()}에
 * 의존하는 공통 {@code CurrentUserIdArgumentResolver}가 그대로 동작한다(하위 호환). 룸 토큰 발급에 필요한
 * nickname/email/role은 {@code @AuthenticationPrincipal LiveUserPrincipal}로 꺼낸다.
 *
 * <p>{@code tokenId}·{@code sessionId}를 싣는 것은 {@link TokenRevocationFilter}가 폐기를 조회할
 * 자리가 JWT 필터보다 <b>뒤</b>이기 때문이다. 두 값은 {@code JwtTokenProvider.parseToken}이 이미
 * 돌려주는 것이라 재파싱이 아니고, 토큰을 식별하는 값이지 그 자체로 입장권이 아니다(서명이 없으면
 * 아무것도 열지 못한다).
 *
 * <p>email(PII)을 담으므로 이 객체를 로그에 남기지 않는다.
 */
public record LiveUserPrincipal(
        UUID userId,
        UUID tokenId,
        UUID sessionId,
        String role,
        String nickname,
        String email
) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return userId.toString();
    }

    /**
     * email(PII)·nickname이 로그로 유출되지 않도록 마스킹한다. record 기본 toString()은 전 필드를
     * 노출하므로(Spring Security DEBUG·{@code log.info("{}", authentication)} 등) 반드시 오버라이드한다.
     *
     * <p>{@code tokenId}·{@code sessionId}는 적지 않는다 — 둘은 추적에 쓸 수 있는 값이지만, 이
     * 오버라이드의 계약이 "필드를 열거하지 않는다" 이고 거기에 새 필드를 더하면 다음 필드가
     * 추가될 때 같은 판단을 다시 해야 한다. 요청 단위 추적은 MDC의 userId가 맡는다.
     */
    @Override
    public String toString() {
        return "LiveUserPrincipal[userId=" + userId + ", role=" + role + ", nickname=***, email=***]";
    }
}
