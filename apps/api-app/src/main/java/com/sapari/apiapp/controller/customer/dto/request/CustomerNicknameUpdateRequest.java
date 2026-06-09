package com.sapari.apiapp.controller.customer.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.customer.command.CustomerNicknameUpdateCommand;

public record CustomerNicknameUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(
                regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
        )
        String nickname
) {

    public CustomerNicknameUpdateCommand toCommand(UUID userId, String accessToken) {
        return new CustomerNicknameUpdateCommand(
                userId,
                nickname,
                accessToken
        );
    }
}
