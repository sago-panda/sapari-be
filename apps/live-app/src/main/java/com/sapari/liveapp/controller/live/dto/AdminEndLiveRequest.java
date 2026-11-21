package com.sapari.liveapp.controller.live.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.live.command.AdminEndLiveCommand;

/** 관리자 종료 사유 — 감사 기록에만 남고 응답·이벤트로 나가지 않는다. */
public record AdminEndLiveRequest(
        @NotBlank(message = "종료 사유는 필수입니다.")
        @Size(max = 500, message = "종료 사유는 500자 이하여야 합니다.")
        // Postgres text 는 0x00 을 받지 않는다 — 감사 INSERT 가 실패하면 종료까지 롤백돼 방송이 계속된다.
        @Pattern(regexp = "[^\\x00]*", message = "종료 사유에 쓸 수 없는 문자가 있습니다.")
        String reason
) {
    public AdminEndLiveCommand toCommand(UUID roomId, UUID adminId) {
        return new AdminEndLiveCommand(roomId, adminId, reason.strip());
    }
}
