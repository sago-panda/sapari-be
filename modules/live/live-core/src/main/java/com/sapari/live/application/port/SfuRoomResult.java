package com.sapari.live.application.port;

public record SfuRoomResult(
        String sfuRoomId,
        int maxParticipants,
        boolean created
) {}
