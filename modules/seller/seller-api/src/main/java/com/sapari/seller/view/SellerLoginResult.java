package com.sapari.seller.view;

import java.util.UUID;

public record SellerLoginResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
