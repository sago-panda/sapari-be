package com.sapari.apiapp.controller.seller.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sapari.seller.command.SellerNicknameUpdateCommand;

public record SellerNicknameUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
        String nickname
) {

    public SellerNicknameUpdateCommand toCommand(UUID userId) {
        return new SellerNicknameUpdateCommand(
                userId,
                nickname
        );
    }
}
