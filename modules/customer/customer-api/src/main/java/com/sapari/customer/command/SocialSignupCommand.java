package com.sapari.customer.command;

import java.time.LocalDate;

public record SocialSignupCommand(
        String phoneNumber,
        String email,
        String nickname,
        String name,
        LocalDate birthDate,
        String gender,
        String profileImageUrl,
        boolean privacyAgreed,
        boolean marketingAgreed
) {
}
