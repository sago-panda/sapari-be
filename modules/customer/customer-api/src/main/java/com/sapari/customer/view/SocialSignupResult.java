package com.sapari.customer.view;

import java.util.UUID;

public record SocialSignupResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
