package com.sapari.apiapp.controller.customer.dto.response;

import java.util.UUID;

import com.sapari.customer.result.SocialSignupResult;

public record SocialSignupResponse(
        UUID userId
) {

    public static SocialSignupResponse from(SocialSignupResult result) {
        return new SocialSignupResponse(result.userId());
    }
}
