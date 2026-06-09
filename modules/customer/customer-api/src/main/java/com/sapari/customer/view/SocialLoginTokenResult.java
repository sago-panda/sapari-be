package com.sapari.customer.view;

import java.util.UUID;

public record SocialLoginTokenResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
