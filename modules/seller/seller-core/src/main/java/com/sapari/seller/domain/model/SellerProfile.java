package com.sapari.seller.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.springframework.util.Assert;

import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;

public record SellerProfile(
        UUID sellerProfileId,
        UUID userId,
        SellerApprovalStatus status,
        String storeName,
        String businessNumber,
        SellerBusinessType businessType,
        String rejectionReason,
        Instant approvedAt
) {

    public static SellerProfile createPending(
            UUID userId,
            String storeName,
            String businessNumber,
            SellerBusinessType businessType
    ) {
        Assert.notNull(userId, "userId는 필수입니다.");
        Assert.hasText(storeName, "storeName은 필수입니다.");
        Assert.hasText(businessNumber, "businessNumber는 필수입니다.");
        Assert.notNull(businessType, "businessType은 필수입니다.");

        return new SellerProfile(
                null,
                userId,
                SellerApprovalStatus.PENDING,
                storeName,
                businessNumber,
                businessType,
                null,
                null
        );
    }
}
