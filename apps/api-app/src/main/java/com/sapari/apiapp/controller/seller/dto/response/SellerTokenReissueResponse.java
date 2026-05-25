package com.sapari.apiapp.controller.seller.dto.response;

import java.util.UUID;

import com.sapari.seller.result.SellerTokenReissueResult;

public record SellerTokenReissueResponse(
        UUID userId
) {

    public static SellerTokenReissueResponse from(SellerTokenReissueResult result) {
        return new SellerTokenReissueResponse(result.userId());
    }
}
