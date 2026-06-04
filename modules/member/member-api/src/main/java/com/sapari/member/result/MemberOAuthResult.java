package com.sapari.member.result;

public record MemberOAuthResult(
        MemberOAuthResultType type,
        String loginCode,
        String signupSid
) {

    public static MemberOAuthResult loginSuccess(String loginCode) {
        return new MemberOAuthResult(MemberOAuthResultType.LOGIN_SUCCESS, loginCode, null);
    }

    public static MemberOAuthResult signupRequired(String signupSid) {
        return new MemberOAuthResult(MemberOAuthResultType.SIGNUP_REQUIRED, null, signupSid);
    }
}
