package com.sapari.live.command;

import java.util.UUID;

public record StartLiveCommand(
        UUID roomId,
        UUID sellerId
) {
}
