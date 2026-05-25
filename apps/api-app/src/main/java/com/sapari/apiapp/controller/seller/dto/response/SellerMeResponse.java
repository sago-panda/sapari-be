package com.sapari.apiapp.controller.seller.dto.response;

import java.time.LocalDate;
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
        Boolean marketingAgreed
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
                result.marketingAgreed()
        );
    }
}
