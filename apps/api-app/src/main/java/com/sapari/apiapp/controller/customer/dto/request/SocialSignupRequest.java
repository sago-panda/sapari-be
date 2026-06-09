package com.sapari.apiapp.controller.customer.dto.request;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.customer.command.SocialSignupCommand;

public record SocialSignupRequest(
        @NotBlank(message = "전화번호는 필수입니다.")
        @Pattern(regexp = "^010\\d{8}$", message = "전화번호는 010으로 시작하는 숫자 11자리여야 합니다.")
        String phoneNumber,

        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(
                regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
        )
        String nickname,

        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 20, message = "이름은 20자 이하여야 합니다.")
        String name,

        @NotNull(message = "생년월일은 필수입니다.")
        LocalDate birthDate,

        @NotNull(message = "성별은 필수입니다.")
        @Pattern(regexp = "MALE|FEMALE", message = "성별 형식이 올바르지 않습니다.")
        String gender,

        Boolean marketingAgreed
) {

    public SocialSignupCommand toCommand() {
        return new SocialSignupCommand(
                phoneNumber,
                email,
                nickname,
                name,
                birthDate,
                gender,
                Boolean.TRUE.equals(marketingAgreed)
        );
    }
}
