package com.sapari.member.command;

import java.util.UUID;

public record MemberLogoutCommand(
        UUID userId,
        String accessToken
) {
}
