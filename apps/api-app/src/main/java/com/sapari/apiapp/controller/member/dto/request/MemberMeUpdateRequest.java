package com.sapari.apiapp.controller.member.dto.request;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.member.command.MemberMeUpdateCommand;

public record MemberMeUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
        String nickname,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
        String name,

        @NotNull(message = "생년월일은 필수입니다.")
        LocalDate birthDate,

        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^\\d{11}$", message = "전화번호는 숫자 11자리여야 합니다.")
        String phoneNumber,

        @Size(max = 500, message = "프로필 이미지 키는 500자 이하여야 합니다.")
        String profileImageKey,

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotNull(message = "마케팅 동의 여부는 필수입니다.")
        Boolean marketingAgreed
) {

    public MemberMeUpdateCommand toCommand(UUID userId) {
        return new MemberMeUpdateCommand(
                userId,
                nickname,
                name,
                birthDate,
                phoneNumber,
                profileImageKey,
                email,
                marketingAgreed
        );
    }
}
