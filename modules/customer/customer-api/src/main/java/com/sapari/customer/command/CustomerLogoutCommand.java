package com.sapari.customer.command;

public record CustomerLogoutCommand(
        String accessToken
) {
}
