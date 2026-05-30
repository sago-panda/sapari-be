package com.sapari.user.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.util.Assert;

@Builder(toBuilder = true)
public record User(
        UUID userId,
        UserRole role,
        UserStatus status,
        String nickname,
        String name,
        LocalDate birthDate,
        UserGender gender,
        String phoneNumber,
        String profileImageKey,
        String email,
        UserGrade grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        Instant suspendedUntil,
        String suspensionReason,
        Instant deletedAt,
        Instant personalDataPurgedAt,
        ProviderType provider,
        String providerId,
        String providerEmail,
        Instant providerCreatedAt
) {

    public static User createSocialMember(
            String nickname,
            String name,
            LocalDate birthDate,
            UserGender gender,
            String phoneNumber,
            String email,
            Boolean marketingAgreed,
            ProviderType provider,
            String providerId,
            String providerEmail
    ) {
        validateRequiredProfile(nickname, name, birthDate, phoneNumber, email);

        return User.builder()
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .nickname(nickname)
                .name(name)
                .birthDate(birthDate)
                .gender(gender)
                .phoneNumber(phoneNumber)
                .email(email)
                .grade(UserGrade.BRONZE)
                .pointBalance(0)
                .marketingAgreed(Boolean.TRUE.equals(marketingAgreed))
                .provider(provider)
                .providerId(providerId)
                .providerEmail(providerEmail)
                .providerCreatedAt(providerCreatedAt)
                .build();
    }

    public static User createSeller(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String email,
            Boolean marketingAgreed
    ) {
        validateRequiredProfile(nickname, name, birthDate, phoneNumber, email);

        return User.builder()
                .role(UserRole.SELLER)
                .status(UserStatus.ACTIVE)
                .nickname(nickname)
                .name(name)
                .birthDate(birthDate)
                .phoneNumber(phoneNumber)
                .email(email)
                .grade(UserGrade.BRONZE)
                .pointBalance(0)
                .marketingAgreed(Boolean.TRUE.equals(marketingAgreed))
                .build();
    }

    public User updateProfile(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String profileImageKey,
            String email,
            Boolean marketingAgreed
    ) {
        validateRequiredProfile(nickname, name, birthDate, phoneNumber, email);
        Assert.notNull(marketingAgreed, "marketingAgreed은 필수입니다.");

        return toBuilder()
                .nickname(nickname)
                .name(name)
                .birthDate(birthDate)
                .phoneNumber(phoneNumber)
                .profileImageKey(profileImageKey)
                .email(email)
                .marketingAgreed(marketingAgreed)
                .build();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public boolean isSeller() {
        return role == UserRole.SELLER;
    }

    private static void validateRequiredProfile(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String email
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
    }
}
