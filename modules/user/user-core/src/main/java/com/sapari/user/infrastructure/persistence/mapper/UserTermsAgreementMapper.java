package com.sapari.user.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.user.domain.model.UserTermsAgreement;
import com.sapari.user.infrastructure.persistence.entity.UserTermsAgreementEntity;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface UserTermsAgreementMapper {

    default UserTermsAgreementEntity toEntity(UserTermsAgreement agreement) {
        return UserTermsAgreementEntity.of(
                agreement.userId(),
                agreement.termsId(),
                agreement.agreed(),
                agreement.agreedAt()
        );
    }

    @Mapping(target = "userTermsAgreementId", source = "id")
    UserTermsAgreement toDomain(UserTermsAgreementEntity entity);
}
