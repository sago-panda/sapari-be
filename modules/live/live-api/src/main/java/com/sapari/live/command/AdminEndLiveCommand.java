package com.sapari.live.command;

import java.util.UUID;

public record AdminEndLiveCommand(
        UUID roomId,
        UUID adminId,
        String reason
) {
}
