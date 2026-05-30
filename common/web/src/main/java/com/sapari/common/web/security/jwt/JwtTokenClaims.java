package com.sapari.common.web.security.jwt;

import java.time.Instant;
import java.util.UUID;

public record JwtTokenClaims(
        UUID userId,
        String role,
        JwtTokenType tokenType,
        String nickname,
        String email,
        Instant expiresAt
) {
}
