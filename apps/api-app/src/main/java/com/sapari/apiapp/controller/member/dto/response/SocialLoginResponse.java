package com.sapari.apiapp.controller.member.dto.response;

import java.util.UUID;

import com.sapari.member.result.SocialLoginTokenResult;

public record SocialLoginResponse(
        UUID userId
) {

    public static SocialLoginResponse from(SocialLoginTokenResult result) {
        return new SocialLoginResponse(result.userId());
    }
}
