package com.sapari.live.command;

import java.util.UUID;

public record PrepareIngressCommand(
        UUID roomId,
        UUID sellerId
) {
}
