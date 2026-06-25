package com.sapari.streamingapp.websocket.auth;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ReactiveTokenBlacklistChecker;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * WebSocket 핸드셰이크 JWT 인증 (§7.1 step 1~4).
 *
 * <p>검증 통과분만 {@link ChatPrincipal}로 변환하고, 실패는 {@link WebSocketAuthException}로 신호한다
 * (핸들러가 연결 거부로 매핑). 발급측(common/auth)과 동일한 코덱({@link JwtTokenProvider})으로 검증해
 * 서명·secret·claim 형식 drift를 컴파일 강제로 막는다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticator {

    private final JwtTokenProvider tokenProvider;                 // common/security-jwt — 발급측과 동일 코덱(검증)
    private final ReactiveTokenBlacklistChecker blacklistChecker; // chat-core reactive 포트 — 이벤트루프 안전(블로킹 checker 금지)

    public Mono<ChatPrincipal> authenticate(String token) {
        // parseToken은 서명·만료·issuer·필수 claim을 검증하고 실패 시 동기 throw → fromCallable로 onError 신호로 전환
        return Mono.fromCallable(() -> tokenProvider.parseToken(token))
                .onErrorMap(e -> new WebSocketAuthException("유효하지 않은 토큰"))
                .flatMap(this::verifyAndBuild);
    }

    private Mono<ChatPrincipal> verifyAndBuild(JwtTokenClaims claims) {
        // ACCESS 토큰만 허용 — REFRESH로 WS 연결 시도는 거부(REFRESH는 토큰 재발급 전용)
        if (claims.tokenType() != JwtTokenType.ACCESS) {
            return Mono.error(new WebSocketAuthException("ACCESS 토큰이 아님(tokenType=" + claims.tokenType() + ")"));
        }
        // 로그아웃·강제만료 토큰 차단. Redis 장애는 fail-open(통과) — 가용성 우선이며,
        // 핸드셰이크 뒤 room ended/live(fail-closed) 게이트가 전면 장애를 막는 backstop이라 단독 장애일 때만 통과(L11/L575).
        return blacklistChecker.isBlacklisted(claims.tokenId().toString())
                .onErrorReturn(false)
                .flatMap(revoked -> revoked
                        ? Mono.error(new WebSocketAuthException("폐기된 토큰(jti=" + claims.tokenId() + ")"))
                        : Mono.just(new ChatPrincipal(
                                claims.userId(), claims.role(), claims.nickname(), claims.email())));
    }
}
