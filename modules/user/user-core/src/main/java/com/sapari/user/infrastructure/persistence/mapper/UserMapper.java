package com.sapari.user.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

import com.sapari.user.domain.model.User;
import com.sapari.user.model.UserRole;
import com.sapari.user.infrastructure.persistence.entity.UserEntity;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface UserMapper {

    default UserEntity toEntity(User user) {
        if (user.role() == UserRole.SELLER) {
            UserEntity seller = UserEntity.createSeller(
                    user.nickname(),
                    user.name(),
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
            seller.updateWithdrawalState(
                    user.status(),
                    user.deletedAt()
            );
            return seller;
        }

        UserEntity userEntity = UserEntity.createSocialCustomer(
                user.nickname(),
                user.name(),
                user.birthDate(),
                user.gender(),
                user.phoneNumber(),
                user.email(),
                user.profileImageKey(),
                user.marketingAgreed(),
                user.provider(),
                user.providerId(),
                user.providerEmail(),
                user.providerCreatedAt(),
                user.nicknameChangedAt()
        );
        userEntity.updateProfile(
                user.nickname(),
                user.name(),
                user.birthDate(),
                user.phoneNumber(),
                user.profileImageKey(),
                user.email(),
                user.marketingAgreed(),
                user.nicknameChangedAt()
        );
        userEntity.updateWithdrawalState(
                user.status(),
                user.deletedAt()
        );
        return userEntity;
    }

    @Mapping(target = "userId", source = "id")
    User toDomain(UserEntity entity);

    default void updateEntityFromDomain(@MappingTarget UserEntity entity, User user) {
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
        entity.updateWithdrawalState(
                user.status(),
                user.deletedAt()
        );
    }
}
