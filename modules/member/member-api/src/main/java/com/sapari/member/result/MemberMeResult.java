package com.sapari.member.result;

import java.time.LocalDate;
import java.util.UUID;

public record MemberMeResult(
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
        String provider
) {
}
