package com.sapari.live.domain.model;

import java.time.LocalDateTime;

public sealed interface LiveStatus
        permits LiveStatus.Scheduled, LiveStatus.Live, LiveStatus.Ended, LiveStatus.Suspended {

    record Scheduled(LocalDateTime scheduledAt) implements LiveStatus {}
    record Live(LocalDateTime startedAt, String sfuRoomId, String egressId, String hlsUrl) implements LiveStatus {}
    record Ended(LocalDateTime startedAt, LocalDateTime endedAt, String hlsArchiveUrl) implements LiveStatus {}
    record Suspended(LocalDateTime suspendedAt, String reason) implements LiveStatus {}

    default boolean canTransitionTo(LiveStatus next){
        return switch (this){
            case Scheduled s -> next instanceof Live || next instanceof Suspended;
            case Live l     -> next instanceof Ended || next instanceof Suspended;
            case Suspended s -> next instanceof Live || next instanceof Ended;
            case Ended e    -> false;
        };
    }
}
