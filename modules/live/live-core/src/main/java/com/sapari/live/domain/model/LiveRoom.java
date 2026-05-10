package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

import com.sapari.live.domain.model.LiveStatus.Scheduled;

@Builder
public record LiveRoom(
        UUID id,
        UUID sellerId,
        String title,
        String description,
        StreamInfo streamInfo,
        LiveStatus status,
        LocalDateTime scheduledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static LiveRoom create(
            UUID sellerId,
            String title,
            String description,
            LocalDateTime scheduledAt
    ){
        return new LiveRoom(
                null,
                sellerId,
                title,
                description,
                null,
                new Scheduled(LocalDateTime.now()),
                scheduledAt,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    public LiveRoom withSfuRoomId(String sfuRoomId) {
        //streamInfo 정보 없는 경우 sfuRoomId만 추가, 그 외에는 기존 값 복사하고 sfuRoomId만 교체
        StreamInfo updated = (this.streamInfo == null) ?
                new StreamInfo(sfuRoomId, null, null) : new StreamInfo(sfuRoomId, this.streamInfo.egressId(), this.streamInfo.hlsUrl());
        return new LiveRoom(
                id, sellerId, title, description,
                updated,status,
                scheduledAt, createdAt, LocalDateTime.now()
        );
    }

    public LiveRoom startLive(StreamInfo newStreamInfo){
        var nextStatus = new LiveStatus.Live(
                LocalDateTime.now(),
                newStreamInfo.sfuRoomId(),
                newStreamInfo.egressId(),
                newStreamInfo.hlsUrl()
        );

        return new LiveRoom(
                id,
                sellerId,
                title,
                description,
                newStreamInfo,
                nextStatus,
                scheduledAt,
                createdAt,
                updatedAt
        );
    }

}
