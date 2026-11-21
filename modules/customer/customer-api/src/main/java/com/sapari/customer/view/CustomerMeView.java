package com.sapari.customer.view;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerMeView(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String gender,
        String phoneNumber,
        String profileImageUrl,
        String email,
        String role,
        String status,
        String grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        String provider
) {
}
