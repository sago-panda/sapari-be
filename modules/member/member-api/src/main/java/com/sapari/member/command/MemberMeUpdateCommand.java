package com.sapari.member.command;

import java.time.LocalDate;
import java.util.UUID;

public record MemberMeUpdateCommand(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String phoneNumber,
        String profileImageKey,
        String email,
        Boolean marketingAgreed
) {
}
