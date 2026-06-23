package com.sapari.customer.view;

public record CustomerNicknameUpdateResult(
        CustomerMeView customer,
        String accessToken
) {
}
