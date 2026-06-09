package com.sapari.seller.result;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

public record SellerMeResult(
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
        String businessType,
        String approvalStatus,
        String rejectionReason,
        Instant approvedAt
) {
}
