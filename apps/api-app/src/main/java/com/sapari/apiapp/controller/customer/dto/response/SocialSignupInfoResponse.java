package com.sapari.apiapp.controller.customer.dto.response;

import java.time.LocalDate;

import com.sapari.customer.view.SocialSignupInfoView;

public record SocialSignupInfoResponse(
        String phoneNumber,
        String name,
        String email,
        String nickname,
        String profileImageUrl,
        String gender,
        LocalDate birthDate
) {

    public static SocialSignupInfoResponse from(SocialSignupInfoView result) {
        return new SocialSignupInfoResponse(
                result.phoneNumber(),
                result.name(),
                result.email(),
                result.nickname(),
                result.profileImageUrl(),
                result.gender(),
                result.birthDate()
        );
    }
}
