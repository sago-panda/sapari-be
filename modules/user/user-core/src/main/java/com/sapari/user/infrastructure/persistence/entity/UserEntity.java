package com.sapari.user.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;
import org.springframework.util.Assert;

import com.sapari.user.domain.model.ProviderType;
import com.sapari.user.domain.model.UserGrade;
import com.sapari.user.domain.model.UserRole;
import com.sapari.user.domain.model.UserStatus;

@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "users_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserRole role = UserRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(nullable = false, length = 10)
    private String nickname;

    @Column(length = 20)
    private String name;

    private LocalDate birthDate;

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

    private LocalDateTime suspendedUntil;

    @Column(columnDefinition = "text")
    private String suspensionReason;

    private LocalDateTime deletedAt;

    private LocalDateTime personalDataPurgedAt;

    @Enumerated(EnumType.STRING)
    private ProviderType provider;

    private String providerId;

    private String providerEmail;

    private LocalDateTime providerCreatedAt;

    public static UserEntity createSocialMember(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String email,
            Boolean marketingAgreed,
            ProviderType provider,
            String providerId,
            String providerEmail
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");

        UserEntity user = new UserEntity();

        user.role = UserRole.USER;
        user.status = UserStatus.ACTIVE;
        user.nickname = nickname;
        user.name = name;
        user.birthDate = birthDate;
        user.phoneNumber = phoneNumber;
        user.email = email;
        user.grade = UserGrade.BRONZE;
        user.pointBalance = 0;
        user.marketingAgreed = Boolean.TRUE.equals(marketingAgreed);
        user.provider = provider;
        user.providerId = providerId;
        user.providerEmail = providerEmail;
        user.providerCreatedAt = LocalDateTime.now();

        return user;
    }

    public static UserEntity createSeller(
            String nickname,
            String name,
            LocalDate birthDate,
            String phoneNumber,
            String email,
            Boolean marketingAgreed
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");

        UserEntity user = new UserEntity();

        user.role = UserRole.SELLER;
        user.status = UserStatus.ACTIVE;
        user.nickname = nickname;
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
            Boolean marketingAgreed
    ) {
        Assert.hasText(nickname, "닉네임은 필수입니다.");
        Assert.hasText(name, "name은 필수입니다.");
        Assert.notNull(birthDate, "birthDate은 필수입니다.");
        Assert.hasText(phoneNumber, "phoneNumber은 필수입니다.");
        Assert.hasText(email, "email은 필수입니다.");
        Assert.notNull(marketingAgreed, "marketingAgreed은 필수입니다.");

        this.nickname = nickname;
        this.name = name;
        this.birthDate = birthDate;
        this.phoneNumber = phoneNumber;
        this.profileImageKey = profileImageKey;
        this.email = email;
        this.marketingAgreed = marketingAgreed;
    }
}
