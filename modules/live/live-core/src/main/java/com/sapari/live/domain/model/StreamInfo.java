package com.sapari.live.domain.model;

public record StreamInfo(
        String sfuRoomId,
        String egressId,
        String hlsUrl
) {
    public static StreamInfo of(String sfuRoomId, String egressId, String hlsUrl){
        return new StreamInfo(sfuRoomId, egressId, hlsUrl);
    }
}
