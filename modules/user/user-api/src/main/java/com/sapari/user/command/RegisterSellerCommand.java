package com.sapari.user.command;

import java.time.LocalDate;

public record RegisterSellerCommand(
        String nickname,
        String name,
        LocalDate birthDate,
        String phoneNumber,
        String email,
        Boolean marketingAgreed
) {
}
