package com.sapari.live.command;

import java.util.UUID;

public record EnterLiveCommand(
        UUID roomId,
        String title
) {
}
