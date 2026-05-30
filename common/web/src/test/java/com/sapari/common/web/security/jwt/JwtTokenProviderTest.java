package com.sapari.common.web.security.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 토큰 제공자 테스트")
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String ISSUER = "auth-service-test";

    @Test
    @DisplayName("Access Token 생성 시 userId, role, ACCESS 타입을 포함한다")
    void createAccessTokenContainsUserIdRoleAndAccessTokenType() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        UUID userId = UUID.randomUUID();
        JwtSubject subject = new JwtSubject(userId, "USER");

        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);

        // then
        assertEquals(userId, claims.userId());
        assertEquals("USER", claims.role());
        assertEquals(JwtTokenType.ACCESS, claims.tokenType());
        assertTrue(claims.expiresAt().isAfter(Instant.now()));
    }

    @Test
    @DisplayName("Refresh Token 생성 시 REFRESH 타입을 포함한다")
    void createRefreshTokenContainsRefreshTokenType() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");

        // when
        String token = jwtTokenProvider.createRefreshToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);

        // then
        assertEquals(JwtTokenType.REFRESH, claims.tokenType());
    }

    @Test
    @DisplayName("유효한 토큰의 남은 만료 시간은 0보다 크다")
    void getRemainingExpirationReturnsPositiveDurationWhenTokenIsValid() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");

        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);
        Duration remainingExpiration = Duration.between(timeProvider().now(), claims.expiresAt());

        // then
        assertTrue(remainingExpiration.compareTo(Duration.ZERO) > 0);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenIsInvalid() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);

        // when, then
        assertThrows(RuntimeException.class, () -> jwtTokenProvider.parseToken("invalid.jwt.token"));
    }

    @Test
    @DisplayName("만료된 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenIsExpired() throws InterruptedException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(1, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");
        String token = jwtTokenProvider.createAccessToken(subject);

        Thread.sleep(1100);

        // when, then
        assertThrows(RuntimeException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("issuer가 다르면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenIssuerDoesNotMatch() {
        // given
        JwtTokenProvider tokenIssuer = createProvider(3600, 1209600);
        JwtTokenProvider tokenValidator = new JwtTokenProvider(new JwtProperties(
                "different-issuer",
                SECRET,
                3600L,
                1209600L
        ), timeProvider());
        String token = tokenIssuer.createAccessToken(new JwtSubject(UUID.randomUUID(), "USER"));

        // when, then
        assertThrows(RuntimeException.class, () -> tokenValidator.parseToken(token));
    }

    @Test
    @DisplayName("subject가 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenSubjectIsMissing() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        String token = createTokenWithoutSubject();

        // when, then
        assertThrows(RuntimeException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("tokenType claim이 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenTypeIsMissing() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        String token = createTokenWithoutTokenType();

        // when, then
        assertThrows(RuntimeException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("JWT 설정 객체는 yml 또는 환경변수에서 주입된 값을 그대로 가진다")
    void jwtPropertiesKeepsConfiguredValues() {
        // given & when
        JwtProperties jwtProperties = new JwtProperties(ISSUER, SECRET, 3600L, 1209600L);

        // then
        assertEquals(ISSUER, jwtProperties.issuer());
        assertEquals(SECRET, jwtProperties.secret());
        assertEquals(3600L, jwtProperties.accessTokenExpirationSeconds());
        assertEquals(1209600L, jwtProperties.refreshTokenExpirationSeconds());
    }

    private JwtTokenProvider createProvider(
            long accessTokenExpirationSeconds,
            long refreshTokenExpirationSeconds
    ) {
        return new JwtTokenProvider(new JwtProperties(
                ISSUER,
                SECRET,
                accessTokenExpirationSeconds,
                refreshTokenExpirationSeconds
        ), timeProvider());
    }

    private TimeProvider timeProvider() {
        return new TimeProvider(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    }

    private String createTokenWithoutSubject() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(secretKey())
                .compact();
    }

    private String createTokenWithoutTokenType() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(UUID.randomUUID().toString())
                .claim("role", "USER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(secretKey())
                .compact();
    }

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }
}
