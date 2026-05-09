package com.sapari.live.domain.model;

public record StreamInfo(
        String sfuRoomId,
        String egressId,
        String hlsUrl
) {
}
