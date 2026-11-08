package com.sapari.liveapp.controller.live.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sapari.live.command.UpdateLiveCommand;

/**
 * 예약 방 수정(전체 교체). 필드 규칙은 방 생성({@link CreateRoomRequest})과 같아야 한다 — 지금은 생성과 같은
 * 필수 여부만 있다. 길이·미래 시각·썸네일 도메인 규칙은 SPR-156 이 생성·수정이 함께 쓸 정의로 만든다.
 * 여기서 따로 베끼지 않는다(두 정의가 갈린다). 판매자 닉네임·송출 유형은 수정 대상이 아니다.
 */
public record UpdateRoomRequest(
        @NotBlank(message = "방송 제목은 필수입니다.")
        String title,
        @NotBlank(message = "방송 설명은 필수입니다.")
        String description,
        @NotBlank(message = "썸네일은 필수입니다.")
        String thumbnailUrl,
        @NotNull(message = "방송 예정 시간은 필수입니다.")
        Instant scheduledAt
) {
    public UpdateLiveCommand toCommand(UUID roomId, UUID sellerId) {
        return new UpdateLiveCommand(roomId, sellerId, title, description, thumbnailUrl, scheduledAt);
    }
}
