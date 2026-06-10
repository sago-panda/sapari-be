package com.sapari.seller.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.infrastructure.persistence.entity.SellerProfileEntity;

@Mapper(componentModel = "spring")
public interface SellerProfileMapper {

    default SellerProfileEntity toEntity(SellerProfile sellerProfile) {
        return SellerProfileEntity.of(
                sellerProfile.userId(),
                sellerProfile.status(),
                sellerProfile.storeName(),
                sellerProfile.businessNumber(),
                sellerProfile.businessType(),
                sellerProfile.rejectionReason(),
                sellerProfile.approvedAt()
        );
    }

    @Mapping(target = "sellerProfileId", source = "id")
    SellerProfile toDomain(SellerProfileEntity entity);

    default void updateEntityFromDomain(@MappingTarget SellerProfileEntity entity, SellerProfile sellerProfile) {
        entity.update(
                sellerProfile.status(),
                sellerProfile.storeName(),
                sellerProfile.businessNumber(),
                sellerProfile.businessType(),
                sellerProfile.rejectionReason(),
                sellerProfile.approvedAt()
        );
    }
}
