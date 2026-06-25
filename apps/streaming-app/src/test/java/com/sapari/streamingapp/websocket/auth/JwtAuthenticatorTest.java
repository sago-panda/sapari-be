package com.sapari.streamingapp.websocket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.chat.application.port.ReactiveTokenBlacklistChecker;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JwtAuthenticatorTest {

    private JwtTokenProvider tokenProvider;
    private ReactiveTokenBlacklistChecker blacklistChecker;
    private JwtAuthenticator authenticator;

    private final UUID userId = UUID.randomUUID();
    private final UUID jti = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tokenProvider = mock(JwtTokenProvider.class);
        blacklistChecker = mock(ReactiveTokenBlacklistChecker.class);
        authenticator = new JwtAuthenticator(tokenProvider, blacklistChecker);
    }

    private JwtTokenClaims claims(JwtTokenType type) {
        return new JwtTokenClaims(
                userId, UUID.randomUUID(), jti, "BUYER", type,
                "구매자닉", "buyer@example.com", Instant.parse("2026-06-25T00:00:00Z"));
    }

    @Test
    @DisplayName("유효한 ACCESS 토큰 + 비-blacklist → ChatPrincipal(신원) 반환")
    void valid_access_token_authenticates() {
        when(tokenProvider.parseToken("t")).thenReturn(claims(JwtTokenType.ACCESS));
        when(blacklistChecker.isBlacklisted(jti.toString())).thenReturn(Mono.just(false));

        StepVerifier.create(authenticator.authenticate("t"))
                .assertNext(p -> {
                    assertThat(p.userId()).isEqualTo(userId);
                    assertThat(p.role()).isEqualTo("BUYER");
                    assertThat(p.nickname()).isEqualTo("구매자닉");
                    assertThat(p.email()).isEqualTo("buyer@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("토큰 파싱 실패(만료·서명오류 등) → WebSocketAuthException")
    void invalid_token_rejected() {
        when(tokenProvider.parseToken("bad")).thenThrow(new RuntimeException("expired"));

        StepVerifier.create(authenticator.authenticate("bad"))
                .expectError(WebSocketAuthException.class)
                .verify();
    }

    @Test
    @DisplayName("REFRESH 토큰으로 연결 시도 → 거부, blacklist 조회 도달 안 함")
    void refresh_token_rejected_before_blacklist() {
        when(tokenProvider.parseToken("r")).thenReturn(claims(JwtTokenType.REFRESH));

        StepVerifier.create(authenticator.authenticate("r"))
                .expectError(WebSocketAuthException.class)
                .verify();
        verify(blacklistChecker, never()).isBlacklisted(any());
    }

    @Test
    @DisplayName("blacklist 등재 토큰(로그아웃) → 거부")
    void blacklisted_token_rejected() {
        when(tokenProvider.parseToken("t")).thenReturn(claims(JwtTokenType.ACCESS));
        when(blacklistChecker.isBlacklisted(jti.toString())).thenReturn(Mono.just(true));

        StepVerifier.create(authenticator.authenticate("t"))
                .expectError(WebSocketAuthException.class)
                .verify();
    }

    @Test
    @DisplayName("blacklist 조회 Redis 에러 → fail-open(통과) — 가용성 우선, ended/live가 backstop")
    void blacklist_redis_error_fails_open() {
        when(tokenProvider.parseToken("t")).thenReturn(claims(JwtTokenType.ACCESS));
        when(blacklistChecker.isBlacklisted(jti.toString()))
                .thenReturn(Mono.error(new RuntimeException("redis down")));

        StepVerifier.create(authenticator.authenticate("t"))
                .assertNext(p -> assertThat(p.userId()).isEqualTo(userId))
                .verifyComplete();
    }
}
