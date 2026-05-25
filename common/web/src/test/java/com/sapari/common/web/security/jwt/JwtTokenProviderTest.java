package com.sapari.common.web.security.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

        // then
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals(userId, jwtTokenProvider.getUserId(token));
        assertEquals("USER", jwtTokenProvider.getRole(token));
        assertEquals(JwtTokenType.ACCESS, jwtTokenProvider.getTokenType(token));
    }

    @Test
    @DisplayName("Refresh Token 생성 시 REFRESH 타입을 포함한다")
    void createRefreshTokenContainsRefreshTokenType() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");

        // when
        String token = jwtTokenProvider.createRefreshToken(subject);

        // then
        assertTrue(jwtTokenProvider.validateToken(token));
        assertEquals(JwtTokenType.REFRESH, jwtTokenProvider.getTokenType(token));
    }

    @Test
    @DisplayName("유효한 토큰의 남은 만료 시간은 0보다 크다")
    void getRemainingExpirationReturnsPositiveDurationWhenTokenIsValid() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");

        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        Duration remainingExpiration = jwtTokenProvider.getRemainingExpiration(token);

        // then
        assertTrue(remainingExpiration.compareTo(Duration.ZERO) > 0);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 유효하지 않다")
    void validateTokenReturnsFalseWhenTokenIsInvalid() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);

        // when
        boolean valid = jwtTokenProvider.validateToken("invalid.jwt.token");

        // then
        assertFalse(valid);
    }

    @Test
    @DisplayName("만료된 토큰은 유효하지 않다")
    void validateTokenReturnsFalseWhenTokenIsExpired() throws InterruptedException {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(1, 1209600);
        JwtSubject subject = new JwtSubject(UUID.randomUUID(), "USER");
        String token = jwtTokenProvider.createAccessToken(subject);

        Thread.sleep(1100);

        // when
        boolean valid = jwtTokenProvider.validateToken(token);

        // then
        assertFalse(valid);
    }

    @Test
    @DisplayName("issuer가 다르면 유효하지 않다")
    void validateTokenReturnsFalseWhenIssuerDoesNotMatch() {
        // given
        JwtTokenProvider tokenIssuer = createProvider(3600, 1209600);
        JwtTokenProvider tokenValidator = new JwtTokenProvider(new JwtProperties(
                "different-issuer",
                SECRET,
                3600L,
                1209600L
        ));
        String token = tokenIssuer.createAccessToken(new JwtSubject(UUID.randomUUID(), "USER"));

        // when
        boolean valid = tokenValidator.validateToken(token);

        // then
        assertFalse(valid);
    }

    @Test
    @DisplayName("subject가 없으면 유효하지 않다")
    void validateTokenReturnsFalseWhenSubjectIsMissing() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        String token = createTokenWithoutSubject();

        // when
        boolean valid = jwtTokenProvider.validateToken(token);

        // then
        assertFalse(valid);
    }

    @Test
    @DisplayName("tokenType claim이 없으면 유효하지 않다")
    void validateTokenReturnsFalseWhenTokenTypeIsMissing() {
        // given
        JwtTokenProvider jwtTokenProvider = createProvider(3600, 1209600);
        String token = createTokenWithoutTokenType();

        // when
        boolean valid = jwtTokenProvider.validateToken(token);

        // then
        assertFalse(valid);
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
        ));
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
