package com.sapari.customer.command;

import java.time.LocalDate;

public record CustomerOAuthCommand(
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
