package com.sapari.live.domain.model;

import java.time.LocalDateTime;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended {

    record Scheduled(LocalDateTime scheduledAt) implements LiveStatus {}
    record Live(LocalDateTime startedAt, String sfuRoomId, String egressId, String hlsUrl) implements LiveStatus {}
    record Ended(LocalDateTime startedAt, LocalDateTime endedAt, String hlsArchiveUrl) implements LiveStatus {}
    record Suspended(LocalDateTime suspendedAt, String reason) implements LiveStatus {}
}
