package com.sapari.apiapp.controller.customer.dto.response;

import java.util.UUID;

import com.sapari.customer.result.CustomerTokenReissueResult;

public record TokenReissueResponse(
        UUID userId
) {

    public static TokenReissueResponse from(CustomerTokenReissueResult result) {
        return new TokenReissueResponse(result.userId());
    }
}
