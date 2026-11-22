package com.sapari.live.command;

import java.util.UUID;

public record ChangeSellerLivePermissionCommand(
        UUID sellerId,
        UUID adminId,
        boolean liveEnabled,
        String reason
) {
}
