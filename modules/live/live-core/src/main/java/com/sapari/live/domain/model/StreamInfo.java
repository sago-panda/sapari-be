package com.sapari.live.domain.model;

public record StreamInfo(
        String sfuRoomId,
        String egressId,
        String hlsUrl,
        String hlsArchiveUrl
) {
    public StreamInfo {
        if (sfuRoomId == null || sfuRoomId.isBlank()) throw new IllegalArgumentException("sfuRoomId는 필수입니다.");
    }

    public static StreamInfo of(String sfuRoomId, String egressId, String hlsUrl, String hlsArchiveUrl){
        return new StreamInfo(sfuRoomId, egressId, hlsUrl, hlsArchiveUrl);
    }

    public static StreamInfo ofSfuRoomId(String sfuRoomId) {
        return new StreamInfo(sfuRoomId, null, null, null);
    }
}
