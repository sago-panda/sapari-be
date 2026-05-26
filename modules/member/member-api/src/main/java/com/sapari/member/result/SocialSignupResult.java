package com.sapari.member.result;

import java.util.UUID;

public record SocialSignupResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
