package com.sapari.seller.command;

import java.util.UUID;

public record SellerLogoutCommand(
        UUID userId,
        String accessToken
) {
}
