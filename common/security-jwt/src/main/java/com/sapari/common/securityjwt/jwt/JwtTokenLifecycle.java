package com.sapari.common.securityjwt.jwt;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.global.time.TimeProvider;

/**
 * JWT Access/Refresh Token의 발급, 회전, 폐기를 담당하는 공통 생명주기 컴포넌트.
 * 로그인 방식과 도메인 예외는 각 flow module(seller/customer)이 소유하므로 여기서는 다루지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JwtTokenLifecycle {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final SessionRevocationStore sessionRevocationStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;

    /**
     * 새 로그인 sid를 만들고 같은 세션으로 묶인 Access Token과 Refresh Token을 발급한다.
     */
    public IssuedTokenPair issueTokenPair(JwtTokenPrincipal principal) {
        UUID sessionId = UUID.randomUUID();
        JwtSubject subject = toJwtSubject(principal, sessionId);
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        String refreshToken = issueRefreshToken(subject);

        return new IssuedTokenPair(accessToken, refreshToken);
    }

    /**
     * Refresh Token을 파싱하고 회전에 필요한 sid/jti/만료 정보를 반환한다.
     */
    public RefreshSession requireRefreshToken(String refreshToken) {
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);

        return new RefreshSession(
                refreshClaims.userId(),
                refreshClaims.sessionId(),
                refreshClaims.tokenId(),
                refreshClaims.expiresAt()
        );
    }

    /**
     * 검증된 refresh 세션을 같은 sid의 새 Access Token과 Refresh Token으로 회전한다.
     */
    public RotatedRefreshToken rotateRefreshToken(
            RefreshSession refreshSession,
            JwtTokenPrincipal principal
    ) {
        // RefreshSession과 principal은 독립 인자이므로, 새 토큰 발급 전 같은 사용자 세션인지 보장한다.
        validateRefreshSessionOwner(refreshSession, principal);

        JwtSubject subject = toJwtSubject(principal, refreshSession.sessionId());
        RefreshTokenRotation refreshTokenRotation = rotateRefreshToken(subject, refreshSession);
        String accessToken = jwtTokenProvider.createAccessToken(subject);

        return new RotatedRefreshToken(
                accessToken,
                refreshTokenRotation.refreshToken(),
                refreshTokenRotation.refreshTokenMaxAgeSeconds()
        );
    }

    /**
     * Access Token을 파싱하고 이후 폐기/재발급에 필요한 sid/jti/만료 정보를 반환한다.
     */
    public AccessSession requireAccessToken(String accessToken) {
        JwtTokenClaims claims = parseAccessToken(accessToken);

        return new AccessSession(claims.userId(), claims.sessionId(), claims.tokenId(), claims.expiresAt());
    }

    /**
     * 로그아웃한 sid 세션을 폐기해 같은 세션의 Access Token까지 차단한다.
     */
    public void revokeSession(String accessToken) {
        AccessSession accessSession = requireAccessToken(accessToken);

        refreshTokenStore.deleteBySessionId(accessSession.sessionId());
        sessionRevocationStore.revoke(accessSession.sessionId());
    }

    /**
     * 기존 Access Token jti를 폐기하고 같은 sid로 새 Access Token을 발급한다.
     */
    public String replaceAccessToken(AccessSession accessSession, JwtTokenPrincipal principal) {
        blacklistAccessToken(accessSession);

        return jwtTokenProvider.createAccessToken(toJwtSubject(principal, accessSession.sessionId()));
    }

    private JwtTokenClaims parseRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new JwtTokenLifecycleException("Refresh Token is required.");
        }

        JwtTokenClaims claims = parseToken(refreshToken);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new JwtTokenLifecycleException("Refresh Token has invalid token type.");
        }

        return claims;
    }

    private JwtTokenClaims parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new JwtTokenLifecycleException("Access Token is required.");
        }

        JwtTokenClaims claims = parseToken(accessToken);

        if (claims.tokenType() != JwtTokenType.ACCESS) {
            throw new JwtTokenLifecycleException("Access Token has invalid token type.");
        }

        return claims;
    }

    private JwtTokenClaims parseToken(String token) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new JwtTokenLifecycleException("JWT token is invalid.", e);
        }
    }

    private String issueRefreshToken(JwtSubject subject) {
        String refreshToken = jwtTokenProvider.createRefreshToken(subject);
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);

        refreshTokenStore.save(
                refreshClaims.sessionId(),
                refreshClaims.tokenId(),
                getRemainingExpiration(refreshClaims)
        );

        return refreshToken;
    }

    private RefreshTokenRotation rotateRefreshToken(JwtSubject subject, RefreshSession refreshSession) {
        String refreshToken = jwtTokenProvider.createRefreshTokenForRotation(subject, refreshSession.expiresAt());
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);
        Duration refreshTokenTtl = getRemainingExpiration(refreshClaims);

        if (refreshTokenTtl.toMillis() < 1) {
            throw new JwtTokenLifecycleException("Refresh Token is expired.");
        }

        boolean rotated = refreshTokenStore.rotate(
                refreshSession.sessionId(),
                refreshSession.tokenId(),
                refreshClaims.tokenId(),
                refreshTokenTtl
        );

        if (!rotated) {
            // 저장된 refresh jti와 맞지 않으면 재사용으로 보고 해당 sid 세션을 폐기한다.
            refreshTokenStore.deleteBySessionId(refreshSession.sessionId());
            sessionRevocationStore.revoke(refreshSession.sessionId());
            throw new JwtTokenLifecycleException("Refresh Token rotation failed.");
        }

        return new RefreshTokenRotation(refreshToken, refreshTokenTtl.toSeconds());
    }

    private void validateRefreshSessionOwner(RefreshSession refreshSession, JwtTokenPrincipal principal) {
        if (!refreshSession.userId().equals(principal.userId())) {
            throw new JwtTokenLifecycleException("Refresh Token owner does not match.");
        }
    }

    private void blacklistAccessToken(AccessSession accessSession) {
        Duration remainingExpiration = getRemainingExpiration(accessSession.expiresAt());

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklist.save(accessSession.tokenId(), remainingExpiration);
    }

    private Duration getRemainingExpiration(JwtTokenClaims claims) {
        return getRemainingExpiration(claims.expiresAt());
    }

    private Duration getRemainingExpiration(Instant expiresAt) {
        Duration remainingExpiration = Duration.between(timeProvider.now(), expiresAt);

        if (remainingExpiration.isNegative()) {
            return Duration.ZERO;
        }

        return remainingExpiration;
    }

    private JwtSubject toJwtSubject(JwtTokenPrincipal principal, UUID sessionId) {
        return new JwtSubject(
                principal.userId(),
                sessionId,
                principal.role(),
                principal.nickname(),
                principal.email()
        );
    }

    /**
     * 신규 세션 발급 결과로 내려줄 Access Token과 Refresh Token 쌍이다.
     */
    public record IssuedTokenPair(
            String accessToken,
            String refreshToken
    ) {
    }

    /**
     * 검증된 Refresh Token의 sid/jti/만료 정보를 사용자 조회와 회전에 넘기기 위한 값이다.
     */
    public record RefreshSession(
            UUID userId,
            UUID sessionId,
            UUID tokenId,
            Instant expiresAt
    ) {
    }

    /**
     * RTR 성공 후 응답에 필요한 새 토큰과 refresh cookie max-age 정보다.
     */
    public record RotatedRefreshToken(
            String accessToken,
            String refreshToken,
            long refreshTokenMaxAgeSeconds
    ) {
    }

    /**
     * 검증된 Access Token의 sid/jti/만료 정보를 이후 폐기와 재발급에 넘기기 위한 값이다.
     */
    public record AccessSession(
            UUID userId,
            UUID sessionId,
            UUID tokenId,
            Instant expiresAt
    ) {
    }

    private record RefreshTokenRotation(
            String refreshToken,
            long refreshTokenMaxAgeSeconds
    ) {
    }
}
