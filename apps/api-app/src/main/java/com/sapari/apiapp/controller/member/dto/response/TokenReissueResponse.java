package com.sapari.apiapp.controller.member.dto.response;

import java.util.UUID;

import com.sapari.member.result.MemberTokenReissueResult;

public record TokenReissueResponse(
        UUID userId
) {

    public static TokenReissueResponse from(MemberTokenReissueResult result) {
        return new TokenReissueResponse(result.userId());
    }
}
