package com.sapari.seller.command;

import java.time.LocalDate;

public record SellerSignupCommand(
        String email,
        String password,
        String nickname,
        String name,
        String phoneNumber,
        LocalDate birthDate,
        Boolean marketingAgreed
) {
}
