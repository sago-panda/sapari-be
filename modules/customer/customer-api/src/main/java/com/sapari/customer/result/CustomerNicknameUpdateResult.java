package com.sapari.customer.result;

public record CustomerNicknameUpdateResult(
        CustomerMeResult customer,
        String accessToken
) {
}
