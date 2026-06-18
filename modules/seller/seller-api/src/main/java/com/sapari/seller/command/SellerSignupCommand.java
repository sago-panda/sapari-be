package com.sapari.seller.command;

import java.time.LocalDate;

import com.sapari.seller.model.SellerBusinessType;

public record SellerSignupCommand(
        String email,
        String password,
        String nickname,
        String name,
        String phoneNumber,
        LocalDate birthDate,
        Boolean marketingAgreed,
        String storeName,
        String businessNumber,
        LocalDate businessStartDate,
        SellerBusinessType businessType
) {
}
