package com.sapari.user.command;

import java.time.LocalDate;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

public record RegisterSocialCustomerCommand(
        String nickname,
        String name,
        LocalDate birthDate,
        UserGender gender,
        String phoneNumber,
        String email,
        String profileImageKey,
        boolean privacyAgreed,
        boolean marketingAgreed,
        ProviderType provider,
        String providerId,
        String providerEmail
) {
}
