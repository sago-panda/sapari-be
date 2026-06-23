package com.sapari.apiapp.controller.customer.dto.response;

import java.time.LocalDate;
import java.util.UUID;

import com.sapari.customer.view.CustomerMeView;

public record CustomerMeResponse(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String gender,
        String phoneNumber,
        String profileImageKey,
        String email,
        String role,
        String status,
        String grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        String provider
) {

    public static CustomerMeResponse from(CustomerMeView result) {
        return new CustomerMeResponse(
                result.userId(),
                result.nickname(),
                result.name(),
                result.birthDate(),
                result.gender(),
                result.phoneNumber(),
                result.profileImageKey(),
                result.email(),
                result.role(),
                result.status(),
                result.grade(),
                result.pointBalance(),
                result.marketingAgreed(),
                result.provider()
        );
    }
}
