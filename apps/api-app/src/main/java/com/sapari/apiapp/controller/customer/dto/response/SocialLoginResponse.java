package com.sapari.apiapp.controller.customer.dto.response;

import java.util.UUID;

import com.sapari.customer.view.SocialLoginTokenResult;

public record SocialLoginResponse(
        UUID userId
) {

    public static SocialLoginResponse from(SocialLoginTokenResult result) {
        return new SocialLoginResponse(result.userId());
    }
}
