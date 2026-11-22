package com.sapari.liveapp.controller.live.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.sapari.live.command.ChangeSellerLivePermissionCommand;

/** 판매자 라이브 권한 변경 — 사유 검증은 {@link AdminEndLiveRequest} 와 같고, 감사 기록에만 남는다. */
public record AdminSellerLivePermissionRequest(
        @NotNull(message = "liveEnabled 는 필수입니다.")
        Boolean liveEnabled,
        @NotBlank(message = "변경 사유는 필수입니다.")
        @Size(max = 500, message = "변경 사유는 500자 이하여야 합니다.")
        // Postgres text 는 0x00 을 받지 않는다 — 감사 INSERT 가 실패하면 권한 변경까지 롤백된다.
        @Pattern(regexp = "[^\\x00]*", message = "변경 사유에 쓸 수 없는 문자가 있습니다.")
        String reason
) {
    public ChangeSellerLivePermissionCommand toCommand(UUID sellerId, UUID adminId) {
        return new ChangeSellerLivePermissionCommand(sellerId, adminId, liveEnabled, reason.strip());
    }
}
