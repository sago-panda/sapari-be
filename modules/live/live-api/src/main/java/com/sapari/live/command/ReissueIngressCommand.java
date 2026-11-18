package com.sapari.live.command;

import java.util.UUID;

public record ReissueIngressCommand(
        UUID roomId,
        UUID sellerId
) {
}
