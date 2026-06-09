package com.sapari.customer.command;

import java.util.UUID;

public record CustomerLogoutCommand(
        UUID userId,
        String accessToken
) {
}
