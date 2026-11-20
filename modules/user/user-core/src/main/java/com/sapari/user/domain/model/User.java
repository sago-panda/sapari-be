package com.sapari.user.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.util.Assert;

import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

@Builder(toBuilder = true)
public record User(
        UUID userId,
        UserRole role,
        UserStatus status,
        String nickname,
        Instant nicknameChangedAt,
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
        ProviderType provider,
        String providerId,
        String providerEmail,
        Instant providerCreatedAt
) {

    public static User createSocialCustomer(
            String nickname,
            String name,
            LocalDate birthDate,
            UserGender gender,
            String phoneNumber,
            String email,
            String profileImageKey,
            Boolean marketingAgreed,
            ProviderType provider,
            String providerId,
            String providerEmail,
            Instant providerCreatedAt,
            Instant nicknameChangedAt
    ) {
        validateRequiredProfile(nickname, name, phoneNumber, email);
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.notNull(gender, "gender은 필수입니다.");
        Assert.notNull(providerCreatedAt, "providerCreatedAt은 필수입니다.");
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        return User.builder()
                .role(UserRole.USER)
                .status(UserStatus.ACTIVE)
                .nickname(nickname)
                .nicknameChangedAt(nicknameChangedAt)
                .name(name)
                .birthDate(birthDate)
                .gender(gender)
                .phoneNumber(phoneNumber)
                .profileImageKey(profileImageKey)
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
            String phoneNumber,
            String email,
            Boolean marketingAgreed,
            Instant nicknameChangedAt
    ) {
        validateRequiredProfile(nickname, name, phoneNumber, email);
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        return User.builder()
                .role(UserRole.SELLER)
                .status(UserStatus.ACTIVE)
                .nickname(nickname)
                .nicknameChangedAt(nicknameChangedAt)
                .name(name)
                .phoneNumber(phoneNumber)
                .email(email)
                .grade(UserGrade.BRONZE)
                .pointBalance(0)
                .marketingAgreed(Boolean.TRUE.equals(marketingAgreed))
                .build();
    }

    public User updateNickname(String nickname, Instant nicknameChangedAt) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        return toBuilder()
                .nickname(nickname)
                .nicknameChangedAt(nicknameChangedAt)
                .build();
    }

    /** 검증·저장이 끝난 object key로 프로필 이미지 연결을 교체한 새 aggregate를 반환한다. */
    public User updateProfileImageKey(String profileImageKey) {
        Assert.hasText(profileImageKey, "profileImageKey는 필수입니다.");

        return toBuilder()
                .profileImageKey(profileImageKey)
                .build();
    }

    /** 외부 object 삭제와 분리해 DB의 프로필 이미지 연결만 제거한 새 aggregate를 반환한다. */
    public User removeProfileImage() {
        return toBuilder()
                .profileImageKey(null)
                .build();
    }

    /**
     * 회원탈퇴 유예 상태로 전환한다.
     * deletedAt은 실제 삭제 완료 시각이 아니라 30일 유예 기간의 시작 시각으로 사용한다.
     */
    public User requestWithdrawal(Instant deletedAt) {
        Assert.notNull(deletedAt, "deletedAt은 필수입니다.");

        return toBuilder()
                .status(UserStatus.WITHDRAWING)
                .deletedAt(deletedAt)
                .build();
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public boolean isWithdrawing() {
        return status == UserStatus.WITHDRAWING;
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
            String phoneNumber,
            String email
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
    }
}
