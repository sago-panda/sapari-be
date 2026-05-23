package com.sapari.live.infrastructure.persistence.mapper;

import java.time.Instant;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStatus.Ended;
import com.sapari.live.domain.model.LiveStatus.Live;
import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.domain.model.LiveStatus.Suspended;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;

public class LiveRoomMapper {

    public static LiveRoomEntity toEntity(LiveRoom room){
        LiveRoomEntity entity = LiveRoomEntity.builder()
                .sellerId(room.sellerId())
                .title(room.title())
                .description(room.description())
                .sellerNickname(room.sellerNickname())
                .thumbnailUrl(room.thumbnailUrl())
                .liveStatus(toStatusEnum(room.status()))
                .scheduledAt(scheduledAt(room.status()))
                .build();

        applyStatusFields(entity, room.status());

        return entity;
    }

    public static LiveRoom toDomain(LiveRoomEntity entity){
        LiveStatus status = switch (entity.getLiveStatus()){
            case SCHEDULED -> new Scheduled(
                    entity.getScheduledAt()
            );
            case LIVE -> new Live(
                    entity.getStartedAt(),
                    entity.getSfuRoomId(),
                    entity.getEgressId(),
                    entity.getHlsUrl()
            );
            case ENDED -> new Ended(
                    entity.getStartedAt(),
                    entity.getEndedAt(),
                    entity.getHlsUrl()
            );
            case SUSPENDED -> new Suspended(
                    entity.getSuspendedAt(),
                    entity.getSuspendedReason()
            );
        };

        return LiveRoom.builder()
                .id(entity.getId())
                .sellerId(entity.getSellerId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .sellerNickname(entity.getSellerNickname())
                .thumbnailUrl(entity.getThumbnailUrl())
                .status(status)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public static void updateEntityFromDomain(LiveRoomEntity entity, LiveRoom room){
        entity.updateTitle(room.title());
        entity.updateDescription(room.description());
        entity.updateSellerNickname(room.sellerNickname());
        entity.updateThumbnailUrl(room.thumbnailUrl());
        entity.updateScheduledAt(room.scheduledAt());

        if (room.streamInfo() != null) {
            entity.updateStreamInfo(
                    room.streamInfo().sfuRoomId(),
                    room.streamInfo().egressId(),
                    room.streamInfo().hlsUrl()
            );
        }

        applyStatusFields(entity, room.status());

        entity.updateLiveStatus(toStatusEnum(room.status()));
    }

    private static LiveRoomStatus toStatusEnum(LiveStatus status) {
        return switch (status) {
            case LiveStatus.Scheduled s  -> LiveRoomStatus.SCHEDULED;
            case LiveStatus.Live l       -> LiveRoomStatus.LIVE;
            case LiveStatus.Ended e      -> LiveRoomStatus.ENDED;
            case LiveStatus.Suspended s  -> LiveRoomStatus.SUSPENDED;
        };
    }

    private static Instant scheduledAt(LiveStatus status) {
        return status instanceof Scheduled(Instant scheduledAt)
                ? scheduledAt : null;
    }

    private static void applyStatusFields(LiveRoomEntity entity,
                                          LiveStatus status) {
        switch (status) {
            case Scheduled s -> {}

            case Live l -> {
                entity.applyLive(
                        l.startedAt(), l.sfuRoomId(), l.egressId(), l.hlsUrl()
                );
            }
            case Ended e -> {
                entity.applyEnded(
                        e.endedAt(), e.hlsArchiveUrl()
                );
            }
            case Suspended s -> {
                entity.applySuspended(s.suspendedAt(), s.reason());
            }
        }
    }
}
