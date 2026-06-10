package com.sapari.seller.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.infrastructure.persistence.entity.LocalCredentialEntity;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface LocalCredentialMapper {

    default LocalCredentialEntity toEntity(LocalCredential localCredential) {
        return LocalCredentialEntity.of(
                localCredential.userId(),
                localCredential.passwordHash(),
                localCredential.failedLoginCount(),
                localCredential.lockedAt(),
                localCredential.lastChangedAt()
        );
    }

    LocalCredential toDomain(LocalCredentialEntity entity);
}
