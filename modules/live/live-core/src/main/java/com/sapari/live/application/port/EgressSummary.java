package com.sapari.live.application.port;

import java.time.Instant;

public record EgressSummary(
        String egressId,
        String roomName,
        boolean active,
        Instant startedAt
) {
}
