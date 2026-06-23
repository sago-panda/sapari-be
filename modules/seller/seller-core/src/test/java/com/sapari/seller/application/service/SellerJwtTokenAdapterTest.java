package com.sapari.seller.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sapari.common.securityjwt.jwt.JwtProperties;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycle;
import com.sapari.common.securityjwt.jwt.JwtTokenClaims;
import com.sapari.common.securityjwt.jwt.JwtTokenLifecycleException;
import com.sapari.common.securityjwt.jwt.JwtTokenProvider;
import com.sapari.common.securityjwt.jwt.JwtTokenType;
import com.sapari.common.securityjwt.store.AccessTokenBlacklist;
import com.sapari.common.securityjwt.store.RefreshTokenStore;
import com.sapari.common.securityjwt.store.SessionRevocationStore;
import com.sapari.global.time.TimeProvider;
import com.sapari.seller.domain.exception.SellerErrorCode;
import com.sapari.seller.domain.exception.SellerException;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;
import com.sapari.user.view.UserView;

@DisplayName("판매자 JWT 세션 서비스 테스트")
class SellerJwtTokenAdapterTest {

    private static final Instant NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final String SECRET = "test-secret-key-for-seller-session-32bytes";

    private final TimeProvider timeProvider = new TimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
            new JwtProperties("seller-session-test", SECRET, 3600L, 1209600L),
            timeProvider
    );
    private final RefreshTokenStore refreshTokenStore = mock(RefreshTokenStore.class);
    private final SessionRevocationStore sessionRevocationStore = mock(SessionRevocationStore.class);
    private final AccessTokenBlacklist accessTokenBlacklist = mock(AccessTokenBlacklist.class);
    private final JwtTokenLifecycle jwtTokenLifecycle = new JwtTokenLifecycle(
            jwtTokenProvider,
            refreshTokenStore,
            sessionRevocationStore,
            accessTokenBlacklist,
            timeProvider
    );
    private final SellerJwtTokenAdapter service = new SellerJwtTokenAdapter(jwtTokenLifecycle);

    @Test
    @DisplayName("새 로그인 세션 토큰 쌍을 발급하고 refresh token id를 저장한다")
    void issueTokenPairSavesRefreshTokenId() {
        // given
        UserView seller = sellerView(UUID.randomUUID(), "seller");

        // when
        JwtTokenLifecycle.IssuedTokenPair tokenPair = service.issueTokenPair(seller);

        // then
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(tokenPair.accessToken());
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(tokenPair.refreshToken());
        assertThat(accessClaims.tokenType()).isEqualTo(JwtTokenType.ACCESS);
        assertThat(refreshClaims.tokenType()).isEqualTo(JwtTokenType.REFRESH);
        assertThat(refreshClaims.sessionId()).isEqualTo(accessClaims.sessionId());
        assertThat(refreshClaims.tokenId()).isNotEqualTo(accessClaims.tokenId());
        verify(refreshTokenStore).save(
                eq(seller.userId()),
                eq(refreshClaims.sessionId()),
                eq(refreshClaims.tokenId()),
                eq(Duration.between(NOW, refreshClaims.expiresAt()))
        );
    }

    @Test
    @DisplayName("신규 토큰 발급 실패는 refresh token 오류로 변환하지 않는다")
    void issueTokenPairPropagatesLifecycleException() {
        // given
        JwtTokenLifecycle tokenLifecycle = mock(JwtTokenLifecycle.class);
        SellerJwtTokenAdapter adapter = new SellerJwtTokenAdapter(tokenLifecycle);
        JwtTokenLifecycleException exception = new JwtTokenLifecycleException("token issue failed.");
        when(tokenLifecycle.issueTokenPair(any())).thenThrow(exception);

        // when & then
        assertThatThrownBy(() -> adapter.issueTokenPair(sellerView(UUID.randomUUID(), "seller")))
                .isSameAs(exception);
    }

    @Test
    @DisplayName("refresh token 회전 성공 시 새 access/refresh token과 남은 TTL을 반환한다")
    void rotateRefreshTokenReturnsRotatedTokens() {
        // given
        UserView seller = sellerView(UUID.randomUUID(), "seller");
        JwtTokenLifecycle.IssuedTokenPair tokenPair = service.issueTokenPair(seller);
        JwtTokenClaims oldRefreshClaims = jwtTokenProvider.parseToken(tokenPair.refreshToken());
        when(refreshTokenStore.rotate(eq(oldRefreshClaims.sessionId()), eq(oldRefreshClaims.tokenId()), any(), any()))
                .thenReturn(true);
        JwtTokenLifecycle.RefreshSession refreshSession = service.requireRefreshToken(tokenPair.refreshToken());

        // when
        JwtTokenLifecycle.RotatedRefreshToken result =
                service.rotateRefreshToken(refreshSession, seller);

        // then
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(result.accessToken());
        JwtTokenClaims newRefreshClaims = jwtTokenProvider.parseToken(result.refreshToken());
        assertThat(accessClaims.sessionId()).isEqualTo(oldRefreshClaims.sessionId());
        assertThat(newRefreshClaims.sessionId()).isEqualTo(oldRefreshClaims.sessionId());
        assertThat(newRefreshClaims.tokenId()).isNotEqualTo(oldRefreshClaims.tokenId());
        assertThat(newRefreshClaims.expiresAt()).isEqualTo(oldRefreshClaims.expiresAt());
        assertThat(result.refreshTokenMaxAgeSeconds()).isEqualTo(Duration.between(NOW, newRefreshClaims.expiresAt()).toSeconds());
    }

    @Test
    @DisplayName("refresh 세션 사용자와 회전 대상 사용자가 다르면 재발급에 실패한다")
    void rotateRefreshTokenThrowsExceptionWhenSessionOwnerMismatch() {
        // given
        UserView seller = sellerView(UUID.randomUUID(), "seller");
        UserView otherSeller = sellerView(UUID.randomUUID(), "other");
        JwtTokenLifecycle.IssuedTokenPair tokenPair = service.issueTokenPair(seller);
        JwtTokenLifecycle.RefreshSession refreshSession = service.requireRefreshToken(tokenPair.refreshToken());

        // when & then
        assertThatThrownBy(() -> service.rotateRefreshToken(refreshSession, otherSeller))
                .isInstanceOf(SellerException.class)
                .extracting("errorCode")
                .isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenStore, never()).rotate(any(), any(), any(), any());
        verify(sessionRevocationStore, never()).revoke(any());
    }

    @Test
    @DisplayName("refresh token 회전 실패 시 해당 sid 세션을 폐기한다")
    void rotateRefreshTokenRevokesSessionWhenReuseDetected() {
        // given
        UserView seller = sellerView(UUID.randomUUID(), "seller");
        JwtTokenLifecycle.IssuedTokenPair tokenPair = service.issueTokenPair(seller);
        JwtTokenClaims oldRefreshClaims = jwtTokenProvider.parseToken(tokenPair.refreshToken());
        when(refreshTokenStore.rotate(eq(oldRefreshClaims.sessionId()), eq(oldRefreshClaims.tokenId()), any(), any()))
                .thenReturn(false);
        JwtTokenLifecycle.RefreshSession refreshSession = service.requireRefreshToken(tokenPair.refreshToken());

        // when & then
        assertThatThrownBy(() -> service.rotateRefreshToken(refreshSession, seller))
                .isInstanceOf(SellerException.class)
                .extracting("errorCode")
                .isEqualTo(SellerErrorCode.INVALID_REFRESH_TOKEN);
        verify(refreshTokenStore).deleteBySessionId(oldRefreshClaims.userId(), oldRefreshClaims.sessionId());
        verify(sessionRevocationStore).revoke(oldRefreshClaims.sessionId());
    }

    @Test
    @DisplayName("닉네임 변경 시 기존 access token id를 폐기하고 같은 sid로 새 access token을 발급한다")
    void replaceAccessTokenForNicknameBlacklistsOldAccessToken() {
        // given
        UserView seller = sellerView(UUID.randomUUID(), "seller");
        JwtTokenLifecycle.IssuedTokenPair tokenPair = service.issueTokenPair(seller);
        JwtTokenLifecycle.AccessSession accessSession =
                service.requireAccessToken(tokenPair.accessToken());
        UserView savedSeller = sellerView(seller.userId(), "updated");

        // when
        String newAccessToken = service.replaceAccessTokenForNickname(accessSession, savedSeller);

        // then
        JwtTokenClaims oldAccessClaims = jwtTokenProvider.parseToken(tokenPair.accessToken());
        JwtTokenClaims newAccessClaims = jwtTokenProvider.parseToken(newAccessToken);
        assertThat(newAccessClaims.sessionId()).isEqualTo(oldAccessClaims.sessionId());
        assertThat(newAccessClaims.nickname()).isEqualTo("updated");
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(accessTokenBlacklist).save(eq(oldAccessClaims.tokenId()), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.between(NOW, oldAccessClaims.expiresAt()));
    }

    private UserView sellerView(UUID userId, String nickname) {
        return new UserView(
                userId,
                UserRole.SELLER,
                UserStatus.ACTIVE,
                nickname,
                NOW,
                "판매자",
                LocalDate.of(1990, 1, 1),
                null,
                "01012345678",
                null,
                "seller@example.com",
                UserGrade.BRONZE,
                0,
                true,
                null,
                null,
                null
        );
    }
}
