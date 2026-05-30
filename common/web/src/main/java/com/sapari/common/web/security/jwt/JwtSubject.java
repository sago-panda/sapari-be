package com.sapari.common.web.security.jwt;

import java.util.UUID;

public record JwtSubject(
        UUID userId,
        String role,
        String nickname,
        String email
) {
}
