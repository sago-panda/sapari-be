package com.sapari.seller.view;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

import com.sapari.seller.model.SellerApprovalStatus;
import com.sapari.seller.model.SellerBusinessType;

public record SellerMeView(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String phoneNumber,
        String profileImageKey,
        String email,
        String role,
        String status,
        String grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        String storeName,
        String businessNumber,
        SellerBusinessType businessType,
        SellerApprovalStatus approvalStatus,
        String rejectionReason,
        Instant approvedAt
) {
}
