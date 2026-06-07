package com.sapari.apiapp.controller.seller.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import com.sapari.seller.command.SellerNicknameUpdateCommand;

public record SellerNicknameUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Pattern(
                regexp = "^[가-힣A-Za-z0-9]{2,10}$",
                message = "닉네임은 2~10자의 한글, 영문, 숫자만 사용할 수 있습니다."
        )
        String nickname
) {

    public SellerNicknameUpdateCommand toCommand(UUID userId, String accessToken) {
        return new SellerNicknameUpdateCommand(
                userId,
                nickname,
                accessToken
        );
    }
}
