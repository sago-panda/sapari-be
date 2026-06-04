package com.sapari.member.result;

public record MemberNicknameUpdateResult(
        MemberMeResult member,
        String accessToken
) {
}
