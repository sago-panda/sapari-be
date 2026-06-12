package com.sapari.customer.command;

public record CustomerNicknameUpdateCommand(
        String nickname,
        String accessToken
) {
}
