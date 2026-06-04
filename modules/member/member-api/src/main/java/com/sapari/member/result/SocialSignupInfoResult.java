package com.sapari.member.result;

import java.time.LocalDate;

public record SocialSignupInfoResult(
        String phoneNumber,
        String name,
        String email,
        String nickname,
        String profileImageUrl,
        String gender,
        LocalDate birthDate
) {
}
