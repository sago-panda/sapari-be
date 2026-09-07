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
    /** 로그·디버그 출력에 비밀번호와 개인정보가 포함되지 않도록 고정 문자열만 반환한다. */
    @Override
    public String toString() {
        return "SellerSignupCommand[REDACTED]";
    }

}
