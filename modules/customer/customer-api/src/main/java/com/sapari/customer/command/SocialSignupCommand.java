package com.sapari.customer.command;

import java.time.LocalDate;

public record SocialSignupCommand(
        String phoneNumber,
        String email,
        String nickname,
        String name,
        LocalDate birthDate,
        String gender,
        Boolean marketingAgreed
) {

    public boolean isMarketingAgreed() {
        return Boolean.TRUE.equals(marketingAgreed);
    }
}
