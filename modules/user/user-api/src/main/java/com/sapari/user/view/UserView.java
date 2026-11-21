package com.sapari.user.view;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

public record UserView(
        UUID userId,
        UserRole role,
        UserStatus status,
        String nickname,
        Instant nicknameChangedAt,
        String name,
        LocalDate birthDate,
        UserGender gender,
        String phoneNumber,
        String profileImageUrl,
        String email,
        UserGrade grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        ProviderType provider,
        String providerId,
        String providerEmail
) {
}
