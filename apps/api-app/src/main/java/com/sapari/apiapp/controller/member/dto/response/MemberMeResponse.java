package com.sapari.apiapp.controller.member.dto.response;

import java.time.LocalDate;
import java.util.UUID;

import com.sapari.member.result.MemberMeResult;

public record MemberMeResponse(
        UUID userId,
        String nickname,
        String name,
        LocalDate birthDate,
        String phoneNumber,
        String profileImageKey,
        String email,
        String role,
        String status,
        String grade,
        Integer pointBalance,
        Boolean marketingAgreed,
        String provider
) {

    public static MemberMeResponse from(MemberMeResult result) {
        return new MemberMeResponse(
                result.userId(),
                result.nickname(),
                result.name(),
                result.birthDate(),
                result.phoneNumber(),
                result.profileImageKey(),
                result.email(),
                result.role(),
                result.status(),
                result.grade(),
                result.pointBalance(),
                result.marketingAgreed(),
                result.provider()
        );
    }
}
