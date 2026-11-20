package com.sapari.live.command;

import java.util.UUID;

public record IssueThumbnailUploadCommand(
        UUID sellerId,
        String contentType
) {
}
