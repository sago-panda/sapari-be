package com.sapari.common.web.security.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 토큰 제공자 테스트")
class JwtTokenProviderTest {

    private static final String SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String OTHER_SECRET = "other-test-secret-key-for-jwt-provider-32bytes";
    private static final String ISSUER = "auth-service-test";
    private static final Instant FIXED_NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 3600L;
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 1209600L;

    private TimeProvider timeProvider;
    private JwtTokenProvider jwtTokenProvider;
    private UUID userId;
    private JwtSubject subject;

    @BeforeEach
    void setUp() {
        timeProvider = new TimeProvider(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        jwtTokenProvider = createProvider(ACCESS_TOKEN_EXPIRATION_SECONDS, REFRESH_TOKEN_EXPIRATION_SECONDS);
        userId = UUID.randomUUID();
        subject = jwtSubject(userId, "USER");
    }

    @Test
    @DisplayName("Access Token 생성 시 사용자 정보와 ACCESS 타입을 포함한다")
    void createAccessTokenContainsUserIdRoleAndAccessTokenType() {
        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);

        // then
        assertEquals(userId, claims.userId());
        assertEquals("USER", claims.role());
        assertEquals(JwtTokenType.ACCESS, claims.tokenType());
        assertEquals("member", claims.nickname());
        assertEquals("member@example.com", claims.email());
        assertEquals(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS), claims.expiresAt());
    }

    @Test
    @DisplayName("Refresh Token 생성 시 REFRESH 타입을 포함한다")
    void createRefreshTokenContainsRefreshTokenType() {
        // when
        String token = jwtTokenProvider.createRefreshToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);

        // then
        assertEquals(userId, claims.userId());
        assertEquals("USER", claims.role());
        assertEquals(JwtTokenType.REFRESH, claims.tokenType());
        assertNull(claims.nickname());
        assertNull(claims.email());
        assertEquals(FIXED_NOW.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS), claims.expiresAt());
    }

    @Test
    @DisplayName("유효한 토큰의 남은 만료 시간은 0보다 크다")
    void getRemainingExpirationReturnsPositiveDurationWhenTokenIsValid() {
        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        JwtTokenClaims claims = jwtTokenProvider.parseToken(token);
        Duration remainingExpiration = Duration.between(timeProvider.now(), claims.expiresAt());

        // then
        assertEquals(Duration.ofSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS), remainingExpiration);
    }

    @Test
    @DisplayName("형식이 잘못된 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenIsInvalid() {
        assertThrows(MalformedJwtException.class, () -> jwtTokenProvider.parseToken("invalid.jwt.token"));
    }

    @Test
    @DisplayName("만료된 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenIsExpired() {
        // given
        String token = createExpiredToken();

        // when, then
        assertThrows(ExpiredJwtException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("issuer가 다르면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenIssuerDoesNotMatch() {
        // given
        JwtTokenProvider tokenValidator = createProvider(
                "different-issuer",
                SECRET,
                ACCESS_TOKEN_EXPIRATION_SECONDS,
                REFRESH_TOKEN_EXPIRATION_SECONDS
        );
        String token = jwtTokenProvider.createAccessToken(subject);

        // when, then
        assertThrows(IncorrectClaimException.class, () -> tokenValidator.parseToken(token));
    }

    @Test
    @DisplayName("다른 secret으로 서명한 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenSignatureDoesNotMatch() {
        // given
        String token = createAccessTokenSignedWith(OTHER_SECRET);

        // when, then
        assertThrows(SignatureException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("payload를 위조한 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenPayloadIsTampered() {
        // given
        String token = jwtTokenProvider.createAccessToken(subject);
        String tamperedToken = tamperPayload(token);

        // when, then
        assertThrows(SignatureException.class, () -> jwtTokenProvider.parseToken(tamperedToken));
    }

    @Test
    @DisplayName("subject가 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenSubjectIsMissing() {
        // given
        String token = createTokenWithoutSubject();

        // when, then
        assertThrows(IllegalArgumentException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("tokenType claim이 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenTypeIsMissing() {
        // given
        String token = createTokenWithoutTokenType();

        // when, then
        assertThrows(IllegalArgumentException.class, () -> jwtTokenProvider.parseToken(token));
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
        return createProvider(ISSUER, SECRET, accessTokenExpirationSeconds, refreshTokenExpirationSeconds);
    }

    private JwtTokenProvider createProvider(
            String issuer,
            String secret,
            long accessTokenExpirationSeconds,
            long refreshTokenExpirationSeconds
    ) {
        return new JwtTokenProvider(new JwtProperties(
                issuer,
                secret,
                accessTokenExpirationSeconds,
                refreshTokenExpirationSeconds
        ), timeProvider);
    }

    private JwtSubject jwtSubject(UUID userId, String role) {
        return new JwtSubject(userId, role, "member", "member@example.com");
    }

    private String createExpiredToken() {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW.minusSeconds(3600)))
                .expiration(Date.from(FIXED_NOW.minusSeconds(1)))
                .signWith(secretKey(SECRET))
                .compact();
    }

    private String createAccessTokenSignedWith(String secret) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .signWith(secretKey(secret))
                .compact();
    }

    private String createTokenWithoutSubject() {
        return Jwts.builder()
                .issuer(ISSUER)
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .signWith(secretKey(SECRET))
                .compact();
    }

    private String createTokenWithoutTokenType() {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("role", "USER")
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .signWith(secretKey(SECRET))
                .compact();
    }

    private String tamperPayload(String token) {
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"tampered\"}".getBytes(StandardCharsets.UTF_8));

        return parts[0] + "." + tamperedPayload + "." + parts[2];
    }

    private SecretKey secretKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
