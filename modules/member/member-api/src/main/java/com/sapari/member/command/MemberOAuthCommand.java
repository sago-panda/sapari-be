package com.sapari.member.command;

import java.time.LocalDate;

public record MemberOAuthCommand(
        String provider,
        String providerId,
        String providerEmail,
        String name,
        String nickname,
        String phoneNumber,
        String profileImageUrl,
        String gender,
        LocalDate birthDate
) {
}
