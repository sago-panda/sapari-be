package com.sapari.common.web.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;

@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";
    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String NICKNAME_CLAIM = "nickname";
    private static final String EMAIL_CLAIM = "email";
    private static final String SESSION_ID_CLAIM = "sid";

    private final String issuer;
    private final SecretKey secretKey;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;

    private final TimeProvider timeProvider;

    public JwtTokenProvider(JwtProperties jwtProperties, TimeProvider timeProvider) {
        this.issuer = jwtProperties.issuer();
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationSeconds = jwtProperties.accessTokenExpirationSeconds();
        this.refreshTokenExpirationSeconds = jwtProperties.refreshTokenExpirationSeconds();
        this.timeProvider = timeProvider;
    }

    public String createAccessToken(JwtSubject subject) {
        return createToken(subject, JwtTokenType.ACCESS, accessTokenExpirationSeconds);
    }

    public String createRefreshToken(JwtSubject subject) {
        return createToken(subject, JwtTokenType.REFRESH, refreshTokenExpirationSeconds);
    }

    /**
     * 토큰의 서명, 만료 여부와 jti/sid/tokenType 필수 claims를 검증하고 파싱 결과를 반환
     */
    public JwtTokenClaims parseToken(String token) {
        Claims claims = parseClaims(token);

        return new JwtTokenClaims(
                getRequiredUserId(claims),
                getRequiredSessionId(claims),
                getRequiredTokenId(claims),
                getRequiredRole(claims),
                getRequiredTokenType(claims),
                getOptionalStringClaim(claims, NICKNAME_CLAIM),
                getOptionalStringClaim(claims, EMAIL_CLAIM),
                getRequiredExpiration(claims)
        );
    }

    private String createToken(
            JwtSubject subject,
            JwtTokenType tokenType,
            long expirationSeconds
    ) {
        Instant now = timeProvider.now();
        Instant expiration = now.plusSeconds(expirationSeconds);
        UUID tokenId = UUID.randomUUID();

        JwtBuilder builder = Jwts.builder()
                .id(tokenId.toString())
                .issuer(issuer)
                .subject(subject.userId().toString())
                .claim(SESSION_ID_CLAIM, subject.sessionId().toString())
                .claim(ROLE_CLAIM, subject.role())
                .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration));

        if (tokenType == JwtTokenType.ACCESS) {
            builder
                    .claim(NICKNAME_CLAIM, subject.nickname())
                    .claim(EMAIL_CLAIM, subject.email());
        }

        return builder.signWith(secretKey).compact();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .clock(() -> Date.from(timeProvider.now()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * subject는 사용자 UUID로 사용하므로 비어 있거나 UUID 형식이 아니면 유효하지 않은 토큰으로 처리
     */
    private UUID getRequiredUserId(Claims claims) {
        String subject = claims.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject is required.");
        }

        return UUID.fromString(subject);
    }

    /**
     * Refresh Token 저장소 조회에 필요한 sid claim이 포함되어 있는지 확인
     */
    private UUID getRequiredSessionId(Claims claims) {
        String sessionId = claims.get(SESSION_ID_CLAIM, String.class);

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("JWT sid claim is required.");
        }

        return UUID.fromString(sessionId);
    }

    /**
     * 토큰 단위 폐기에 필요한 jti claim이 포함되어 있는지 확인
     */
    private UUID getRequiredTokenId(Claims claims) {
        String tokenId = claims.getId();

        if (tokenId == null || tokenId.isBlank()) {
            throw new IllegalArgumentException("JWT jti claim is required.");
        }

        return UUID.fromString(tokenId);
    }

    /**
     * 인증 객체의 권한을 만들 때 필요한 role claim이 포함되어 있는지 확인
     */
    private String getRequiredRole(Claims claims) {
        String role = claims.get(ROLE_CLAIM, String.class);

        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("JWT role claim is required.");
        }

        return role;
    }

    /**
     * API 인증에는 Access Token만 사용할 수 있도록 tokenType claim을 enum으로 변환하며 검증
     */
    private JwtTokenType getRequiredTokenType(Claims claims) {
        String tokenType = claims.get(TOKEN_TYPE_CLAIM, String.class);

        if (tokenType == null || tokenType.isBlank()) {
            throw new IllegalArgumentException("JWT tokenType claim is required.");
        }

        return JwtTokenType.valueOf(tokenType);
    }

    private Instant getRequiredExpiration(Claims claims) {
        Date expiration = claims.getExpiration();

        if (expiration == null) {
            throw new IllegalArgumentException("JWT expiration claim is required.");
        }

        return expiration.toInstant();
    }

    private String getOptionalStringClaim(Claims claims, String claimName) {
        return claims.get(claimName, String.class);
    }
}
