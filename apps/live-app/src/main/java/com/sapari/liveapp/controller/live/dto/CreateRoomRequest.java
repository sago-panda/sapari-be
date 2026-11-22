package com.sapari.liveapp.controller.live.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sapari.live.command.CreateLiveCommand;
import com.sapari.live.view.LiveStreamKind;
import com.sapari.liveapp.controller.live.dto.constraint.LiveDescription;
import com.sapari.liveapp.controller.live.dto.constraint.LiveThumbnailUrl;
import com.sapari.liveapp.controller.live.dto.constraint.LiveTitle;

/**
 * 방 생성 요청. 판매자 닉네임은 받지 않는다 — 인증 주체(토큰의 nickname)에서 채운다(SPR-216).
 * 옛 클라이언트가 {@code sellerNickname} 을 보내도 무시된다.
 */
public record CreateRoomRequest(
        @NotBlank(message = "방송 제목은 필수입니다.")
        @LiveTitle
        String title,
        @LiveDescription
        String description,
        @NotBlank(message = "썸네일은 필수입니다.")
        @LiveThumbnailUrl
        String thumbnailUrl,
        @NotNull(message = "방송 예정 시간은 필수입니다.")
        Instant scheduledAt,
        @NotNull(message = "송출 방식은 필수입니다.")
        LiveStreamKind streamType
) {
    public CreateLiveCommand toCommand(UUID sellerId, String sellerNickname) {
        return new CreateLiveCommand(sellerId, title, description, sellerNickname, thumbnailUrl, scheduledAt, streamType);
    }
}
