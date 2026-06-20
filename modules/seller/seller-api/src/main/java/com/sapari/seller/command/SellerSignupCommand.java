package com.sapari.seller.command;

import java.time.LocalDate;

import com.sapari.seller.model.SellerBusinessType;

public record SellerSignupCommand(
        String email,
        String password,
        String passwordConfirm,
        String nickname,
        String name,
        String phoneNumber,
        boolean privacyAgreed,
        boolean marketingAgreed,
        String storeName,
        String businessNumber,
        LocalDate businessStartDate,
        SellerBusinessType businessType
) {
}
