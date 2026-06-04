package com.sapari.seller.infrastructure.persistence.mapper;

import com.sapari.seller.domain.model.LocalCredential;
import com.sapari.seller.infrastructure.persistence.entity.LocalCredentialEntity;

public class LocalCredentialMapper {

    public static LocalCredentialEntity toEntity(LocalCredential localCredential) {
        return LocalCredentialEntity.of(
                localCredential.userId(),
                localCredential.passwordHash(),
                localCredential.failedLoginCount(),
                localCredential.lockedAt(),
                localCredential.lastChangedAt()
        );
    }

    public static LocalCredential toDomain(LocalCredentialEntity entity) {
        return new LocalCredential(
                entity.getUserId(),
                entity.getPasswordHash(),
                entity.getFailedLoginCount(),
                entity.getLockedAt(),
                entity.getLastChangedAt()
        );
    }
}
