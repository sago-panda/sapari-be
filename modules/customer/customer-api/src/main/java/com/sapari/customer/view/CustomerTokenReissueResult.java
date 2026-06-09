package com.sapari.customer.view;

import java.util.UUID;

public record CustomerTokenReissueResult(
        UUID userId,
        String accessToken,
        String refreshToken,
        long refreshTokenMaxAgeSeconds
) {
}
