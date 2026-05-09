package com.sapari.live.command;

import java.time.LocalDateTime;
import java.util.UUID;

public record CreateLiveCommand(
        UUID sellerId,
        String title,
        String description,
        LocalDateTime scheduledAt
) {
}
