package com.sapari.user.infrastructure.security;

import java.util.UUID;

public record JwtUserDetailsInfo(
        UUID userId,
        String role,
        String status
) {
}
