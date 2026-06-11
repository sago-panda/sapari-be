package com.sapari.seller.application.service;

import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.common.securityjwt.jwt.JwtSubject;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.user.view.UserView;

@Service
@RequiredArgsConstructor
public class SellerJwtSessionService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final SessionRevocationStore sessionRevocationStore;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final TimeProvider timeProvider;

    /**
     * 새 로그인 세션 sid를 만들고 Access Token과 Refresh Token을 함께 발급한다.
     */
    public IssuedTokenPair issueTokenPair(UserView seller) {
        UUID sessionId = UUID.randomUUID();
        JwtSubject subject = toJwtSubject(seller, sessionId);
        String accessToken = jwtTokenProvider.createAccessToken(subject);
        String refreshToken = issueRefreshToken(subject);

        return new IssuedTokenPair(accessToken, refreshToken);
    }

    /**
     * Refresh Token을 검증하고 회전에 필요한 세션 정보를 반환한다.
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
    public RotatedRefreshToken rotateRefreshToken(RefreshSession refreshSession, UserView seller) {
        validateRefreshSessionOwner(refreshSession, seller);

        JwtSubject subject = toJwtSubject(seller, refreshSession.sessionId());
        RefreshTokenRotation refreshTokenRotation = rotateRefreshToken(subject, refreshSession);
        String accessToken = jwtTokenProvider.createAccessToken(subject);

        return new RotatedRefreshToken(
                accessToken,
                refreshTokenRotation.refreshToken(),
                refreshTokenRotation.refreshTokenMaxAgeSeconds()
        );
    }

    /**
     * Access Token을 검증하고 토큰에서 나온 사용자와 sid 세션 정보를 반환한다.
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
     * 닉네임 snapshot 변경 후 기존 Access Token을 폐기하고 같은 sid로 새 Access Token을 발급한다.
     */
    public String replaceAccessTokenForNickname(AccessSession accessSession, UserView savedSeller) {
        blacklistAccessToken(accessSession);

        return jwtTokenProvider.createAccessToken(toJwtSubject(savedSeller, accessSession.sessionId()));
    }

    /**
     * Refresh Token 문자열을 파싱하고 refresh 용도 토큰인지 검증한다.
     */
    private JwtTokenClaims parseRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        JwtTokenClaims claims = parseToken(refreshToken, SellerErrorCode.INVALID_REFRESH_TOKEN);

        if (claims.tokenType() != JwtTokenType.REFRESH) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return claims;
    }

    /**
     * Access Token 문자열을 파싱하고 access 용도 토큰인지 검증한다.
     */
    private JwtTokenClaims parseAccessToken(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
        }

        JwtTokenClaims claims = parseToken(accessToken, SellerErrorCode.INVALID_ACCESS_TOKEN);

        if (claims.tokenType() != JwtTokenType.ACCESS) {
            throw new SellerException(SellerErrorCode.INVALID_ACCESS_TOKEN);
        }

        return claims;
    }

    /**
     * JWT 파싱 실패를 판매자 인증 도메인 예외로 변환한다.
     */
    private JwtTokenClaims parseToken(String token, SellerErrorCode errorCode) {
        try {
            return jwtTokenProvider.parseToken(token);
        } catch (RuntimeException e) {
            throw new SellerException(errorCode, e);
        }
    }

    /**
     * 신규 로그인 세션의 Refresh Token을 발급하고 현재 refresh jti를 저장한다.
     */
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

    /**
     * 같은 sid에서 refresh jti만 교체하고 기존 Refresh Token 만료 시각을 유지한다.
     */
    private RefreshTokenRotation rotateRefreshToken(JwtSubject subject, RefreshSession refreshSession) {
        String refreshToken = jwtTokenProvider.createRefreshTokenForRotation(subject, refreshSession.expiresAt());
        JwtTokenClaims refreshClaims = parseRefreshToken(refreshToken);
        Duration refreshTokenTtl = getRemainingExpiration(refreshClaims);

        if (refreshTokenTtl.toMillis() < 1) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
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
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }

        return new RefreshTokenRotation(refreshToken, refreshTokenTtl.toSeconds());
    }

    /**
     * refresh 세션의 사용자와 회전 대상 사용자가 같은지 확인한다.
     */
    private void validateRefreshSessionOwner(RefreshSession refreshSession, UserView seller) {
        if (!refreshSession.userId().equals(seller.userId())) {
            throw new SellerException(SellerErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    /**
     * 단일 Access Token jti를 남은 만료 시간 동안 폐기 목록에 저장한다.
     */
    private void blacklistAccessToken(AccessSession accessSession) {
        Duration remainingExpiration = getRemainingExpiration(accessSession.expiresAt());

        if (remainingExpiration.isZero() || remainingExpiration.isNegative()) {
            return;
        }

        accessTokenBlacklist.save(accessSession.tokenId(), remainingExpiration);
    }

    /**
     * JWT claim의 만료 시각 기준으로 Redis TTL에 사용할 남은 시간을 계산한다.
     */
    private Duration getRemainingExpiration(JwtTokenClaims claims) {
        return getRemainingExpiration(claims.expiresAt());
    }

    /**
     * 만료 시각이 이미 지났으면 0으로 보정해 음수 TTL 저장을 막는다.
     */
    private Duration getRemainingExpiration(Instant expiresAt) {
        Duration remainingExpiration = Duration.between(timeProvider.now(), expiresAt);

        if (remainingExpiration.isNegative()) {
            return Duration.ZERO;
        }

        return remainingExpiration;
    }

    /**
     * 같은 sid로 묶을 JWT subject를 만들고 access token snapshot 값을 포함한다.
     */
    private JwtSubject toJwtSubject(UserView seller, UUID sessionId) {
        return new JwtSubject(seller.userId(), sessionId, seller.role().name(), seller.nickname(), seller.email());
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

    /**
     * 내부 refresh 회전 결과로 새 refresh token과 실제 남은 cookie max-age를 담는다.
     */
    private record RefreshTokenRotation(
            String refreshToken,
            long refreshTokenMaxAgeSeconds
    ) {
    }
}
