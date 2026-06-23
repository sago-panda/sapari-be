package com.sapari.seller.command;

public record SellerNicknameUpdateCommand(
        String nickname,
        String accessToken
) {
}
