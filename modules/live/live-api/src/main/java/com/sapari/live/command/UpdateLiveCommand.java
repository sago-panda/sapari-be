package com.sapari.live.command;

import java.time.Instant;
import java.util.UUID;

public record UpdateLiveCommand(
        UUID roomId,
        UUID sellerId,
        String title,
        String description,
        String thumbnailUrl,
        Instant scheduledAt
) {
}
