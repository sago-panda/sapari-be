package com.sapari.seller.command;

import java.util.UUID;

public record SellerNicknameUpdateCommand(
        UUID userId,
        String nickname
) {
}
