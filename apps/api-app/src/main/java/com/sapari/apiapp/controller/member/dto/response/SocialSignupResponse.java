package com.sapari.apiapp.controller.member.dto.response;

import java.util.UUID;

import com.sapari.member.result.SocialSignupResult;

public record SocialSignupResponse(
        UUID userId
) {

    public static SocialSignupResponse from(SocialSignupResult result) {
        return new SocialSignupResponse(result.userId());
    }
}
