package com.sapari.customer.result;

import java.time.LocalDate;
import java.util.UUID;

public record CustomerMeResult(
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
}
