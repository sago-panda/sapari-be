package com.sapari.seller.result;

import java.util.UUID;

public record SellerLoginResult(
        UUID userId,
        String accessToken,
        String refreshToken
) {
}
