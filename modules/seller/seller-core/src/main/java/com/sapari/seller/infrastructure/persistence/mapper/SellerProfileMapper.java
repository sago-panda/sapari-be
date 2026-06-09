package com.sapari.seller.infrastructure.persistence.mapper;

import com.sapari.seller.domain.model.SellerProfile;
import com.sapari.seller.infrastructure.persistence.entity.SellerProfileEntity;

public class SellerProfileMapper {

    public static SellerProfileEntity toEntity(SellerProfile sellerProfile) {
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

    public static SellerProfile toDomain(SellerProfileEntity entity) {
        return new SellerProfile(
                entity.getId(),
                entity.getUserId(),
                entity.getStatus(),
                entity.getStoreName(),
                entity.getBusinessNumber(),
                entity.getBusinessType(),
                entity.getRejectionReason(),
                entity.getApprovedAt()
        );
    }

    public static void updateEntityFromDomain(SellerProfileEntity entity, SellerProfile sellerProfile) {
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
