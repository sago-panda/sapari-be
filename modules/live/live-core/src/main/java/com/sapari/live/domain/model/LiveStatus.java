package com.sapari.live.domain.model;

import java.time.Instant;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended {

    record Scheduled(Instant scheduledAt) implements LiveStatus {}
    record Live(Instant startedAt, String sfuRoomId, String egressId, String hlsUrl) implements LiveStatus {}
    record Ended(Instant startedAt, Instant endedAt, String hlsArchiveUrl) implements LiveStatus {}
    record Suspended(Instant suspendedAt, String reason) implements LiveStatus {}
}
