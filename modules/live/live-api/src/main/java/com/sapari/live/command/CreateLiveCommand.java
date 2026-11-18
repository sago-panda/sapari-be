package com.sapari.live.command;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.view.LiveStreamKind;

public record CreateLiveCommand(
        UUID sellerId,
        String title,
        String description,
        String sellerNickname,
        String thumbnailUrl,
        Instant scheduledAt,
        LiveStreamKind streamType
) {
}
