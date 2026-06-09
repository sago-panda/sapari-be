package com.sapari.apiapp.controller.seller.dto.response;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

import com.sapari.seller.result.SellerMeResult;

public record SellerMeResponse(
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

    public static SellerMeResponse from(SellerMeResult result) {
        return new SellerMeResponse(
                result.userId(),
                result.nickname(),
                result.name(),
                result.birthDate(),
                result.phoneNumber(),
                result.profileImageKey(),
                result.email(),
                result.role(),
                result.status(),
                result.grade(),
                result.pointBalance(),
                result.marketingAgreed(),
                result.storeName(),
                result.businessNumber(),
                result.businessType(),
                result.approvalStatus(),
                result.rejectionReason(),
                result.approvedAt()
        );
    }
}
