package com.sapari.customer.command;

import java.util.UUID;

public record CustomerNicknameUpdateCommand(
        UUID userId,
        String nickname,
        String accessToken
) {
}
