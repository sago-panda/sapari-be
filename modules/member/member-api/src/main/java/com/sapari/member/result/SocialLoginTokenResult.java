package com.sapari.member.result;

import java.util.UUID;

public record SocialLoginTokenResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
