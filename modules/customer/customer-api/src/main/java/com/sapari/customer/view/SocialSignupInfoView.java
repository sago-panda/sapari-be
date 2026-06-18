package com.sapari.customer.view;

import java.time.LocalDate;

public record SocialSignupInfoView(
        String phoneNumber,
        String name,
        String email,
        String nickname,
        String profileImageUrl,
        String gender,
        LocalDate birthDate
) {
}
