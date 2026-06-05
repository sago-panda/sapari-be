package com.sapari.common.securityjwt.jwt;

import java.time.Instant;
import java.util.UUID;

public record JwtTokenClaims(
        UUID userId,
        UUID sessionId,
        UUID tokenId,
        String role,
        JwtTokenType tokenType,
        String nickname,
        String email,
        Instant expiresAt
) {
}
