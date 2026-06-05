package com.sapari.common.securityjwt.jwt;

import java.util.UUID;

public record JwtSubject(
        UUID userId,
        UUID sessionId,
        String role,
        String nickname,
        String email
) {
}
