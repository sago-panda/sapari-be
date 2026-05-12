package com.sapari.live.command;

import java.util.UUID;

public record EndLiveCommand(
        UUID roomId,
        UUID sellerId
) {
}
