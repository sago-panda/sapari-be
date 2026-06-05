package com.sapari.apiapp.controller.live.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sapari.live.command.CreateLiveCommand;

public record CreateRoomRequest(
        @NotBlank(message = "방송 제목은 필수입니다.")
        String title,
        @NotBlank(message = "방송 설명은 필수입니다.")
        String description,
        @NotBlank(message = "판매자 닉네임은 필수입니다.")
        String sellerNickname,
        @NotBlank(message = "썸네일은 필수입니다.")
        String thumbnailUrl,
        @NotNull(message = "방송 예정 시간은 필수입니다.")
        Instant scheduledAt
) {
    public CreateLiveCommand toCommand(UUID sellerId){
        return new CreateLiveCommand(sellerId, title, description, sellerNickname, thumbnailUrl, scheduledAt);
    }
}
