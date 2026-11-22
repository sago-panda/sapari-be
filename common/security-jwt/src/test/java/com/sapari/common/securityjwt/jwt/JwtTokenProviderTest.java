package com.sapari.common.securityjwt.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sapari.global.time.TimeProvider;

@DisplayName("JWT 토큰 제공자 테스트")
class JwtTokenProviderTest {

    private static final String KID = "key-1";
    private static final KeyPair KEY_PAIR = JwtTestKeys.generateKeyPair();
    private static final KeyPair OTHER_KEY_PAIR = JwtTestKeys.generateKeyPair();
    private static final String HMAC_SECRET = "test-secret-key-for-jwt-provider-32bytes";
    private static final String ISSUER = "auth-service-test";
    private static final Instant FIXED_NOW = Instant.parse("2025-01-01T00:00:00Z");
    private static final long ACCESS_TOKEN_EXPIRATION_SECONDS = 3600L;
    private static final long REFRESH_TOKEN_EXPIRATION_SECONDS = 1209600L;

    private TimeProvider timeProvider;
    private JwtTokenProvider jwtTokenProvider;
    private UUID userId;
    private UUID sessionId;
    private JwtSubject subject;

    @BeforeEach
    void setUp() {
        timeProvider = new TimeProvider(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
        jwtTokenProvider = createProvider(ACCESS_TOKEN_EXPIRATION_SECONDS, REFRESH_TOKEN_EXPIRATION_SECONDS);
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
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
        assertEquals(sessionId, claims.sessionId());
        assertNotNull(claims.tokenId());
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
        assertEquals(sessionId, claims.sessionId());
        assertNotNull(claims.tokenId());
        assertEquals("USER", claims.role());
        assertEquals(JwtTokenType.REFRESH, claims.tokenType());
        assertNull(claims.nickname());
        assertNull(claims.email());
        assertEquals(FIXED_NOW.plusSeconds(REFRESH_TOKEN_EXPIRATION_SECONDS), claims.expiresAt());
    }

    @Test
    @DisplayName("같은 세션의 Access Token과 Refresh Token은 같은 sid와 다른 jti를 가진다")
    void createTokensShareSessionIdAndUseDifferentTokenId() {
        // when
        JwtTokenClaims accessClaims = jwtTokenProvider.parseToken(jwtTokenProvider.createAccessToken(subject));
        JwtTokenClaims refreshClaims = jwtTokenProvider.parseToken(jwtTokenProvider.createRefreshToken(subject));

        // then
        assertEquals(accessClaims.sessionId(), refreshClaims.sessionId());
        assertNotEquals(accessClaims.tokenId(), refreshClaims.tokenId());
    }

    @Test
    @DisplayName("RTR용 Refresh Token은 기존 만료 시각을 유지하고 새 jti를 가진다")
    void createRefreshTokenForRotationKeepsExpirationAndUsesNewTokenId() {
        // given
        JwtTokenClaims previousClaims = jwtTokenProvider.parseToken(jwtTokenProvider.createRefreshToken(subject));

        // when
        JwtTokenClaims rotatedClaims = jwtTokenProvider.parseToken(
                jwtTokenProvider.createRefreshTokenForRotation(subject, previousClaims.expiresAt())
        );

        // then
        assertEquals(previousClaims.sessionId(), rotatedClaims.sessionId());
        assertNotEquals(previousClaims.tokenId(), rotatedClaims.tokenId());
        assertEquals(previousClaims.expiresAt(), rotatedClaims.expiresAt());
        assertEquals(JwtTokenType.REFRESH, rotatedClaims.tokenType());
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
                ACCESS_TOKEN_EXPIRATION_SECONDS,
                REFRESH_TOKEN_EXPIRATION_SECONDS
        );
        String token = jwtTokenProvider.createAccessToken(subject);

        // when, then
        assertThrows(IncorrectClaimException.class, () -> tokenValidator.parseToken(token));
    }

    @Test
    @DisplayName("같은 kid 를 다른 개인키로 서명한 토큰은 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenSignatureDoesNotMatch() {
        // given
        String token = accessTokenBuilder()
                .header().keyId(KID).and()
                .signWith(OTHER_KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();

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
    @DisplayName("jti가 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenTokenIdIsMissing() {
        // given
        String token = createTokenWithoutTokenId();

        // when, then
        assertThrows(IllegalArgumentException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("sid가 없으면 파싱에 실패한다")
    void parseTokenThrowsExceptionWhenSessionIdIsMissing() {
        // given
        String token = createTokenWithoutSessionId();

        // when, then
        assertThrows(IllegalArgumentException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("발급한 토큰 헤더에 RS256 과 서명 kid 를 넣는다")
    void createdTokenHeaderHasRs256AndKid() {
        // when
        String token = jwtTokenProvider.createAccessToken(subject);
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        // then
        assertTrue(header.contains("\"alg\":\"RS256\""), header);
        assertTrue(header.contains("\"kid\":\"" + KID + "\""), header);
    }

    @Test
    @DisplayName("공개키만 가진 검증 전용 provider 는 발급 앱의 토큰을 검증한다")
    void verifyOnlyProviderParsesTokenSignedWithPrivateKey() {
        // given
        JwtTokenProvider verifier = verifyOnlyProvider(verificationKey(KID, KEY_PAIR));
        String token = jwtTokenProvider.createAccessToken(subject);

        // when
        JwtTokenClaims claims = verifier.parseToken(token);

        // then
        assertEquals(userId, claims.userId());
        assertEquals(JwtTokenType.ACCESS, claims.tokenType());
    }

    @Test
    @DisplayName("검증 전용 provider 는 토큰을 발급할 수 없다")
    void verifyOnlyProviderCannotSign() {
        JwtTokenProvider verifier = verifyOnlyProvider(verificationKey(KID, KEY_PAIR));

        assertThrows(IllegalStateException.class, () -> verifier.createAccessToken(subject));
    }

    @Test
    @DisplayName("HMAC(HS256)으로 서명한 이전 방식 토큰은 파싱에 실패한다")
    void parseTokenRejectsHmacSignedToken() {
        // given
        String token = accessTokenBuilder()
                .header().keyId(KID).and()
                .signWith(hmacKey(HMAC_SECRET))
                .compact();

        // when, then
        assertThrows(UnsupportedJwtException.class, () -> jwtTokenProvider.parseToken(token));
    }

    @Test
    @DisplayName("kid 가 없거나 모르는 kid 인 토큰은 파싱에 실패한다")
    void parseTokenRejectsMissingOrUnknownKid() {
        // given
        String withoutKid = accessTokenBuilder()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
        String unknownKid = accessTokenBuilder()
                .header().keyId("unknown").and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();

        // when, then
        assertThrows(UnsupportedJwtException.class, () -> jwtTokenProvider.parseToken(withoutKid));
        assertThrows(UnsupportedJwtException.class, () -> jwtTokenProvider.parseToken(unknownKid));
    }

    @Test
    @DisplayName("키 교체 중에는 신·구 공개키를 모두 가진 검증자가 두 키의 토큰을 모두 받는다")
    void verifierAcceptsBothKeysDuringRotation() {
        // given
        String oldToken = jwtTokenProvider.createAccessToken(subject);
        String newToken = accessTokenBuilder()
                .header().keyId("key-2").and()
                .signWith(OTHER_KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
        JwtTokenProvider verifier = verifyOnlyProvider(
                verificationKey("key-2", OTHER_KEY_PAIR),
                verificationKey(KID, KEY_PAIR));

        // when, then
        assertEquals(userId, verifier.parseToken(oldToken).userId());
        assertEquals(userId, verifier.parseToken(newToken).userId());
    }

    @Test
    @DisplayName("JWT 설정 객체는 yml 또는 환경변수에서 주입된 값을 그대로 가진다")
    void jwtPropertiesKeepsConfiguredValues() {
        // given & when
        JwtProperties jwtProperties = properties(ISSUER, 3600L, 1209600L);

        // then
        assertEquals(ISSUER, jwtProperties.issuer());
        assertEquals(KID, jwtProperties.signingKey().kid());
        assertEquals(KID, jwtProperties.verificationKeys().getFirst().kid());
        assertEquals(3600L, jwtProperties.accessTokenExpirationSeconds());
        assertEquals(1209600L, jwtProperties.refreshTokenExpirationSeconds());
    }

    private JwtTokenProvider createProvider(
            long accessTokenExpirationSeconds,
            long refreshTokenExpirationSeconds
    ) {
        return createProvider(ISSUER, accessTokenExpirationSeconds, refreshTokenExpirationSeconds);
    }

    private JwtTokenProvider createProvider(
            String issuer,
            long accessTokenExpirationSeconds,
            long refreshTokenExpirationSeconds
    ) {
        return new JwtTokenProvider(
                properties(issuer, accessTokenExpirationSeconds, refreshTokenExpirationSeconds), timeProvider);
    }

    private JwtProperties properties(String issuer, long accessTokenExpirationSeconds, long refreshTokenExpirationSeconds) {
        return new JwtProperties(
                issuer,
                new JwtProperties.SigningKey(KID, JwtTestKeys.privateKeyBase64(KEY_PAIR)),
                List.of(verificationKey(KID, KEY_PAIR)),
                accessTokenExpirationSeconds,
                refreshTokenExpirationSeconds
        );
    }

    /** 서명키 없이 공개키만 가진 검증 전용 provider(live-app 과 같은 설정). */
    private JwtTokenProvider verifyOnlyProvider(JwtProperties.VerificationKey... keys) {
        return new JwtTokenProvider(
                new JwtProperties(ISSUER, null, List.of(keys),
                        ACCESS_TOKEN_EXPIRATION_SECONDS, REFRESH_TOKEN_EXPIRATION_SECONDS),
                timeProvider);
    }

    private JwtProperties.VerificationKey verificationKey(String kid, KeyPair keyPair) {
        return new JwtProperties.VerificationKey(kid, JwtTestKeys.publicKeyBase64(keyPair));
    }

    private JwtBuilder accessTokenBuilder() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)));
    }

    private JwtSubject jwtSubject(UUID userId, String role) {
        return new JwtSubject(userId, sessionId, role, "member", "member@example.com");
    }

    private String createExpiredToken() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW.minusSeconds(3600)))
                .expiration(Date.from(FIXED_NOW.minusSeconds(1)))
                .header().keyId(KID).and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String createTokenWithoutSubject() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .claim("sid", sessionId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .header().keyId(KID).and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String createTokenWithoutTokenType() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("role", "USER")
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .header().keyId(KID).and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String createTokenWithoutTokenId() {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("sid", sessionId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .header().keyId(KID).and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String createTokenWithoutSessionId() {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("role", "USER")
                .claim("tokenType", JwtTokenType.ACCESS.name())
                .issuedAt(Date.from(FIXED_NOW))
                .expiration(Date.from(FIXED_NOW.plusSeconds(ACCESS_TOKEN_EXPIRATION_SECONDS)))
                .header().keyId(KID).and()
                .signWith(KEY_PAIR.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    private String tamperPayload(String token) {
        String[] parts = token.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"tampered\"}".getBytes(StandardCharsets.UTF_8));

        return parts[0] + "." + tamperedPayload + "." + parts[2];
    }

    private SecretKey hmacKey(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
