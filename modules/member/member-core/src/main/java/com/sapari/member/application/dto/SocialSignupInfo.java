package com.sapari.member.application.dto;

import java.time.LocalDate;

import com.sapari.member.command.MemberOAuthCommand;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;

public record SocialSignupInfo(
        ProviderType provider,
        String providerId,
        String providerEmail,
        String name,
        String nickname,
        String phoneNumber,
        String profileImageUrl,
        UserGender gender,
        LocalDate birthDate
) {

    public static SocialSignupInfo from(MemberOAuthCommand command) {
        return new SocialSignupInfo(
                ProviderType.valueOf(command.provider()),
                command.providerId(),
                command.providerEmail(),
                command.name(),
                command.nickname(),
                command.phoneNumber(),
                command.profileImageUrl(),
                toGender(command.gender()),
                command.birthDate()
        );
    }

    private static UserGender toGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }

        try {
            return UserGender.valueOf(gender);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
