package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.springframework.util.Assert;

import com.sapari.storage.db.entity.BaseUuidEntity;
import com.sapari.user.model.ProviderType;
import com.sapari.user.model.UserGender;
import com.sapari.user.model.UserGrade;
import com.sapari.user.model.UserRole;
import com.sapari.user.model.UserStatus;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity extends BaseUuidEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false, unique = true, length = 10)
    private String nickname;

    @Column(nullable = false)
    private Instant nicknameChangedAt;

    @Column(length = 20)
    private String name;

    private LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private UserGender gender;

    @Column(nullable = false, unique = true, length = 11)
    private String phoneNumber;

    @Column(length = 500)
    private String profileImageKey;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UserGrade grade = UserGrade.BRONZE;

    @Column(nullable = false)
    private Integer pointBalance = 0;

    @Column(nullable = false)
    private Boolean marketingAgreed = false;

    private Instant suspendedUntil;

    @Column(columnDefinition = "text")
    private String suspensionReason;

    private Instant deletedAt;

    private Instant personalDataPurgedAt;

    @Enumerated(EnumType.STRING)
    private ProviderType provider;

    private String providerId;

    private String providerEmail;

    private Instant providerCreatedAt;

    public static UserEntity createSocialCustomer(
            String nickname,
            String name,
            LocalDate birthDate,
            UserGender gender,
            String phoneNumber,
            String email,
            Boolean marketingAgreed,
            ProviderType provider,
            String providerId,
            String providerEmail,
            Instant providerCreatedAt,
            Instant nicknameChangedAt
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.notNull(gender, "gender은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
        Assert.notNull(providerCreatedAt, "providerCreatedAt은 필수입니다.");
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        UserEntity user = new UserEntity();

        user.role = UserRole.USER;
        user.status = UserStatus.ACTIVE;
        user.nickname = nickname;
        user.nicknameChangedAt = nicknameChangedAt;
        user.name = name;
        user.birthDate = birthDate;
        user.gender = gender;
        user.phoneNumber = phoneNumber;
        user.email = email;
        user.grade = UserGrade.BRONZE;
        user.pointBalance = 0;
        user.marketingAgreed = Boolean.TRUE.equals(marketingAgreed);
        user.provider = provider;
        user.providerId = providerId;
        user.providerEmail = providerEmail;
        user.providerCreatedAt = providerCreatedAt;

        return user;
    }

    public static UserEntity createSeller(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String email,
            Boolean marketingAgreed,
            Instant nicknameChangedAt
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        UserEntity user = new UserEntity();

        user.role = UserRole.SELLER;
        user.status = UserStatus.ACTIVE;
        user.nickname = nickname;
        user.nicknameChangedAt = nicknameChangedAt;
        user.name = name;
        user.birthDate = birthDate;
        user.phoneNumber = phoneNumber;
        user.email = email;
        user.grade = UserGrade.BRONZE;
        user.pointBalance = 0;
        user.marketingAgreed = Boolean.TRUE.equals(marketingAgreed);

        return user;
    }

    public void updateProfile(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String profileImageKey,
            String email,
            Boolean marketingAgreed,
            Instant nicknameChangedAt
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
        Assert.notNull(marketingAgreed, "marketingAgreed은 필수입니다.");
        Assert.notNull(nicknameChangedAt, "nicknameChangedAt은 필수입니다.");

        this.nickname = nickname;
        this.nicknameChangedAt = nicknameChangedAt;
        this.name = name;
        this.birthDate = birthDate;
        this.phoneNumber = phoneNumber;
        this.profileImageKey = profileImageKey;
        this.email = email;
        this.marketingAgreed = marketingAgreed;
    }
}
