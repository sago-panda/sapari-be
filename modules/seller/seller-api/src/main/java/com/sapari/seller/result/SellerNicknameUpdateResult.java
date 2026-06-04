package com.sapari.seller.result;

public record SellerNicknameUpdateResult(
        SellerMeResult seller,
        String accessToken
) {
}
