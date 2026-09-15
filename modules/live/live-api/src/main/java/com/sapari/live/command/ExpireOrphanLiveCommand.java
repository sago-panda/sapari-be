package com.sapari.live.command;

import java.util.UUID;

public record ExpireOrphanLiveCommand(
        UUID roomId
) {
}
