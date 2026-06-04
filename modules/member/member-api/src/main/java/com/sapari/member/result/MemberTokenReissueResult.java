package com.sapari.member.result;

import java.util.UUID;

public record MemberTokenReissueResult(
        UUID userId,
        String accessToken
) {
}
