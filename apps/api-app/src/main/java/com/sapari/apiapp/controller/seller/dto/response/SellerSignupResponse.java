package com.sapari.apiapp.controller.seller.dto.response;

import java.util.UUID;

import com.sapari.seller.result.SellerSignupResult;

public record SellerSignupResponse(
        UUID userId
) {

    public static SellerSignupResponse from(SellerSignupResult result) {
        return new SellerSignupResponse(result.userId());
    }
}
