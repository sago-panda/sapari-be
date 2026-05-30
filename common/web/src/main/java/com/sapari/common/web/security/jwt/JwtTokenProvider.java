package com.sapari.common.web.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;

@Slf4j
@Component
public class JwtTokenProvider {

    private static final String ROLE_CLAIM = "role";
    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String NICKNAME_CLAIM = "nickname";
    private static final String EMAIL_CLAIM = "email";

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
     * 토큰의 서명, 만료 여부, 필수 claims를 검증하고 파싱 결과를 반환
     */
    public JwtTokenClaims parseToken(String token) {
        Claims claims = parseClaims(token);

        return new JwtTokenClaims(
                getRequiredUserId(claims),
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

        JwtBuilder builder = Jwts.builder()
                .issuer(issuer)
                .subject(subject.userId().toString())
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
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (SecurityException | MalformedJwtException e) {
            log.warn("Invalid JWT token", e);
            throw e;
        } catch (ExpiredJwtException e) {
            log.debug("Expired JWT token", e);
            throw e;
        } catch (UnsupportedJwtException e) {
            log.warn("Unsupported JWT token", e);
            throw e;
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty", e);
            throw e;
        } catch (JwtException e) {
            log.warn("JWT token validation failed", e);
            throw e;
        }
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
