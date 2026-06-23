package com.sapari.apiapp.controller.seller.dto.response;

import java.util.UUID;

import com.sapari.seller.view.SellerLoginResult;

public record SellerLoginResponse(
        UUID userId
) {

    public static SellerLoginResponse from(SellerLoginResult result) {
        return new SellerLoginResponse(result.userId());
    }
}
