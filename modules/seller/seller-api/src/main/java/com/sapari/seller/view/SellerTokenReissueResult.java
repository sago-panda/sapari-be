package com.sapari.seller.view;

import java.util.UUID;

public record SellerTokenReissueResult(
        UUID userId,
        String accessToken,
        String refreshToken,
        long refreshTokenMaxAgeSeconds
) {
}
