package com.sapari.member.command;

import java.util.UUID;

public record MemberNicknameUpdateCommand(
        UUID userId,
        String nickname
) {
}
