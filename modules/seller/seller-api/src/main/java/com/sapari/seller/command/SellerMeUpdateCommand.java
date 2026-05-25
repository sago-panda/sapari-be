package com.sapari.seller.command;

import java.time.LocalDate;
import java.util.UUID;

public record SellerMeUpdateCommand(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String phoneNumber,
        String profileImageKey,
        String email,
        Boolean marketingAgreed
) {
}
