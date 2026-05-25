package com.sapari.common.web.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";
    private static final String TOKEN_TYPE_CLAIM = "tokenType";

    private final String issuer;
    private final SecretKey secretKey;
    private final long accessTokenExpirationSeconds;
    private final long refreshTokenExpirationSeconds;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.issuer = jwtProperties.issuer();
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpirationSeconds = jwtProperties.accessTokenExpirationSeconds();
        this.refreshTokenExpirationSeconds = jwtProperties.refreshTokenExpirationSeconds();
    }

    public String createAccessToken(JwtSubject subject) {
        return createToken(subject, JwtTokenType.ACCESS, accessTokenExpirationSeconds);
    }

    public String createRefreshToken(JwtSubject subject) {
        return createToken(subject, JwtTokenType.REFRESH, refreshTokenExpirationSeconds);
    }

    /**
     * 토큰의 서명, 만료 여부, 형식을 검증
     *
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = parseClaims(token);
            validateRequiredClaims(claims);
            return true;
        } catch (SecurityException | MalformedJwtException e) {
            log.info("Invalid JWT token", e);
        } catch (ExpiredJwtException e) {
            log.info("Expired JWT token", e);
        } catch (UnsupportedJwtException e) {
            log.info("Unsupported JWT token", e);
        } catch (IllegalArgumentException e) {
            log.info("JWT token is empty", e);
        } catch (JwtException e) {
            log.info("JWT token validation failed", e);
        }
        return false;
    }

    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    public String getRole(String token) {
        return parseClaims(token).get(ROLE_CLAIM, String.class);
    }

    public JwtTokenType getTokenType(String token) {
        return getRequiredTokenType(parseClaims(token));
    }

    /**
     * 토큰의 남은 만료 시간을 계산
     */
    public Duration getRemainingExpiration(String token) {
        Instant expiration = parseClaims(token).getExpiration().toInstant();
        Duration remainingExpiration = Duration.between(Instant.now(), expiration);

        if (remainingExpiration.isNegative()) {
            return Duration.ZERO;
        }

        return remainingExpiration;
    }

    private String createToken(
            JwtSubject subject,
            JwtTokenType tokenType,
            long expirationSeconds
    ) {
        Instant now = Instant.now();
        Instant expiration = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .issuer(issuer)
                .subject(subject.userId().toString())
                .claim(ROLE_CLAIM, subject.role())
                .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(secretKey)
                .compact();
    }

    private void validateRequiredClaims(Claims claims) {
        validateSubject(claims);
        validateRole(claims);
        getRequiredTokenType(claims);
    }

    /**
     * subject는 사용자 UUID로 사용하므로 비어 있거나 UUID 형식이 아니면 유효하지 않은 토큰으로 처리
     */
    private void validateSubject(Claims claims) {
        String subject = claims.getSubject();

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("JWT subject is required.");
        }

        UUID.fromString(subject);
    }

    /**
     * 인증 객체의 권한을 만들 때 필요한 role claim이 포함되어 있는지 확인
     */
    private void validateRole(Claims claims) {
        String role = claims.get(ROLE_CLAIM, String.class);

        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("JWT role claim is required.");
        }
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
}
