package com.sapari.customer.view;

public record CustomerOAuthResult(
        CustomerOAuthResultType type,
        String loginCode,
        String signupSid
) {

    public static CustomerOAuthResult loginSuccess(String loginCode) {
        return new CustomerOAuthResult(CustomerOAuthResultType.LOGIN_SUCCESS, loginCode, null);
    }

    public static CustomerOAuthResult signupRequired(String signupSid) {
        return new CustomerOAuthResult(CustomerOAuthResultType.SIGNUP_REQUIRED, null, signupSid);
    }
}
