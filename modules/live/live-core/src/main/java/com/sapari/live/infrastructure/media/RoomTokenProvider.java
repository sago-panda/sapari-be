package com.sapari.live.infrastructure.media;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.time.Instant;
import java.util.Date;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.RoomTokenClaims;
import com.sapari.live.application.port.RoomTokenIssuer;
import com.sapari.live.infrastructure.config.RoomTokenProperties;

/**
 * 룸 토큰 RS256 서명기 — {@link RoomTokenIssuer} 구현.
 *
 * <p>개인키({@link PrivateKey} 빈, {@code RoomTokenConfig}에서 파싱)로 서명한다. api-app 인증
 * 토큰({@code JwtTokenProvider}, 같은 RS256 이지만 별도 키쌍)과 서로 통용되지 않는다: 사용자 토큰은 chat 이
 * iss·aud 로 거부하고, 이 토큰은 kid 헤더가 없어 사용자 토큰 검증({@code JwtKeys})이 거부한다 — 여기에 kid 를
 * 넣지 말 것.
 *
 * <p>claim: {@code iss=live}, {@code aud=chat}, {@code sub=userId}, {@code room}, {@code role},
 * {@code owner}, (회원만) {@code nickname}/{@code email}, {@code iat}/{@code exp}. 게스트는
 * nickname/email이 null이라 해당 claim을 생략한다(빈 문자열 대신 부재).
 */
@Component
@RequiredArgsConstructor
public class RoomTokenProvider implements RoomTokenIssuer {

    private static final String ROOM_CLAIM = "room";
    private static final String ROLE_CLAIM = "role";
    private static final String OWNER_CLAIM = "owner";
    private static final String NICKNAME_CLAIM = "nickname";
    private static final String EMAIL_CLAIM = "email";

    private final RoomTokenProperties properties;
    private final PrivateKey roomTokenPrivateKey;
    private final TimeProvider timeProvider;

    @Override
    public String issue(RoomTokenClaims claims) {
        Instant now = timeProvider.now();
        Instant expiresAt = now.plusSeconds(properties.expirationSeconds());

        JwtBuilder builder = Jwts.builder()
                .issuer(properties.issuer())
                .audience().add(properties.audience()).and()
                .subject(claims.userId().toString())
                .claim(ROOM_CLAIM, claims.roomId().toString())
                .claim(ROLE_CLAIM, claims.role())
                .claim(OWNER_CLAIM, claims.owner())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt));

        // 게스트는 nickname/email이 null → claim 자체를 넣지 않는다(부재로 표현).
        if (claims.nickname() != null) {
            builder.claim(NICKNAME_CLAIM, claims.nickname());
        }
        if (claims.email() != null) {
            builder.claim(EMAIL_CLAIM, claims.email());
        }

        return builder.signWith(roomTokenPrivateKey).compact();
    }
}
