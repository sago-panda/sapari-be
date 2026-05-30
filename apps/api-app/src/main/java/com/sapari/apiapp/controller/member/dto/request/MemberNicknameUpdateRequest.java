package com.sapari.apiapp.controller.member.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.sapari.member.command.MemberNicknameUpdateCommand;

public record MemberNicknameUpdateRequest(
        @NotBlank(message = "닉네임은 필수입니다.")
        @Size(max = 10, message = "닉네임은 10자 이하여야 합니다.")
        String nickname
) {

    public MemberNicknameUpdateCommand toCommand(UUID userId) {
        return new MemberNicknameUpdateCommand(
                userId,
                nickname
        );
    }
}
