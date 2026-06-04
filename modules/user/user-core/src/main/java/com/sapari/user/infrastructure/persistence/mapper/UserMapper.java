package com.sapari.user.infrastructure.persistence.mapper;

import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserRole;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;

public class UserMapper {

    public static UserEntity toEntity(User user) {
        if (user.role() == UserRole.SELLER) {
            UserEntity seller = UserEntity.createSeller(
                    user.nickname(),
                    user.name(),
                    user.birthDate(),
                    user.phoneNumber(),
                    user.email(),
                    user.marketingAgreed(),
                    user.nicknameChangedAt()
            );
            seller.updateProfile(
                    user.nickname(),
                    user.name(),
                    user.birthDate(),
                    user.phoneNumber(),
                    user.profileImageKey(),
                    user.email(),
                    user.marketingAgreed(),
                    user.nicknameChangedAt()
            );
            return seller;
        }

        UserEntity member = UserEntity.createSocialMember(
                user.nickname(),
                user.name(),
                user.birthDate(),
                user.gender(),
                user.phoneNumber(),
                user.email(),
                user.marketingAgreed(),
                user.provider(),
                user.providerId(),
                user.providerEmail(),
                user.providerCreatedAt(),
                user.nicknameChangedAt()
        );
        member.updateProfile(
                user.nickname(),
                user.name(),
                user.birthDate(),
                user.phoneNumber(),
                user.profileImageKey(),
                user.email(),
                user.marketingAgreed(),
                user.nicknameChangedAt()
        );
        return member;
    }

    public static User toDomain(UserEntity entity) {
        return User.builder()
                .userId(entity.getId())
                .role(entity.getRole())
                .status(entity.getStatus())
                .nickname(entity.getNickname())
                .nicknameChangedAt(entity.getNicknameChangedAt())
                .name(entity.getName())
                .birthDate(entity.getBirthDate())
                .gender(entity.getGender())
                .phoneNumber(entity.getPhoneNumber())
                .profileImageKey(entity.getProfileImageKey())
                .email(entity.getEmail())
                .grade(entity.getGrade())
                .pointBalance(entity.getPointBalance())
                .marketingAgreed(entity.getMarketingAgreed())
                .suspendedUntil(entity.getSuspendedUntil())
                .suspensionReason(entity.getSuspensionReason())
                .deletedAt(entity.getDeletedAt())
                .personalDataPurgedAt(entity.getPersonalDataPurgedAt())
                .provider(entity.getProvider())
                .providerId(entity.getProviderId())
                .providerEmail(entity.getProviderEmail())
                .providerCreatedAt(entity.getProviderCreatedAt())
                .build();
    }

    public static void updateEntityFromDomain(UserEntity entity, User user) {
        entity.updateProfile(
                user.nickname(),
                user.name(),
                user.birthDate(),
                user.phoneNumber(),
                user.profileImageKey(),
                user.email(),
                user.marketingAgreed(),
                user.nicknameChangedAt()
        );
    }
}
