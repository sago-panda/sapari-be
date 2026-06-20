package com.sapari.user.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.user.domain.model.WithdrawnUserRetention;
import com.sapari.user.infrastructure.persistence.entity.WithdrawnUserRetentionEntity;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.ERROR
)
public interface WithdrawnUserRetentionMapper {

    default WithdrawnUserRetentionEntity toEntity(WithdrawnUserRetention withdrawnUserRetention) {
        return WithdrawnUserRetentionEntity.of(
                withdrawnUserRetention.originalUserId(),
                withdrawnUserRetention.nameMasked(),
                withdrawnUserRetention.emailMasked(),
                withdrawnUserRetention.phoneNumberMasked(),
                withdrawnUserRetention.retentionUntil(),
                withdrawnUserRetention.purgedAt()
        );
    }

    @Mapping(target = "withdrawnUserRetentionId", source = "id")
    WithdrawnUserRetention toDomain(WithdrawnUserRetentionEntity entity);
}
