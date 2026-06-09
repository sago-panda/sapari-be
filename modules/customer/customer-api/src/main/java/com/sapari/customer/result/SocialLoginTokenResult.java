package com.sapari.customer.result;

import java.util.UUID;

public record SocialLoginTokenResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
