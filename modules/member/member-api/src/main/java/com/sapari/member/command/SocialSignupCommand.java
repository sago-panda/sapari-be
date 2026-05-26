package com.sapari.member.command;

import java.time.LocalDate;

public record SocialSignupCommand(
        String phoneNumber,
        String email,
        String nickname,
        String name,
        LocalDate birthDate,
        Boolean marketingAgreed
) {

    public boolean isMarketingAgreed() {
        return Boolean.TRUE.equals(marketingAgreed);
    }
}
