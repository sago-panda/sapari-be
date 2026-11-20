package com.sapari.liveapp.controller.live.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sapari.live.command.UpdateLiveCommand;
import com.sapari.liveapp.controller.live.dto.constraint.LiveDescription;
import com.sapari.liveapp.controller.live.dto.constraint.LiveThumbnailUrl;
import com.sapari.liveapp.controller.live.dto.constraint.LiveTitle;

/**
 * 예약 방 수정(전체 교체). 필드 규칙은 방 생성({@link CreateRoomRequest})과 같은 정의를 쓴다 — 길이는 공용 제약
 * 애너테이션, 미래 시각(LIVE-015)은 {@code LiveRoom}, 썸네일 주소(LIVE-016)는 {@code ThumbnailUrlRule}(SPR-156).
 * 여기서 따로 베끼지 않는다(두 정의가 갈린다). 설명은 생성과 같이 선택이다. 판매자 닉네임·송출 유형은 수정 대상이 아니다.
 */
public record UpdateRoomRequest(
        @NotBlank(message = "방송 제목은 필수입니다.")
        @LiveTitle
        String title,
        @LiveDescription
        String description,
        @NotBlank(message = "썸네일은 필수입니다.")
        @LiveThumbnailUrl
        String thumbnailUrl,
        @NotNull(message = "방송 예정 시간은 필수입니다.")
        Instant scheduledAt
) {
    public UpdateLiveCommand toCommand(UUID roomId, UUID sellerId) {
        return new UpdateLiveCommand(roomId, sellerId, title, description, thumbnailUrl, scheduledAt);
    }
}
