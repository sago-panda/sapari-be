package com.sapari.live.infrastructure.persistence.entity;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sapari.storage.db.entity.BaseUuidEntity;

@Entity
@Getter
@Table(name = "live_rooms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveRoomEntity extends BaseUuidEntity {

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiveRoomStatus liveStatus;

    private String sfuRoomId;
    private String egressId;
    private String hlsUrl;
    private String hlsArchiveUrl;

    private String suspendedReason;

    private Instant suspendedAt;

    private Instant scheduledAt;

    private Instant startedAt;

    private Instant endedAt;

    private int peakViewers;

    private int totalViewers;

    private String vodKey;

    private int vodDurationSeconds;

    @Enumerated(EnumType.STRING)
    private VodStatus vodStatus;

    private boolean isVodPublic;

    @Builder
    public LiveRoomEntity(UUID sellerId, String title, String description, LiveRoomStatus liveStatus,
                          String sfuRoomId,
                          String egressId, String hlsUrl, String hlsArchiveUrl, String suspendedReason,
                          Instant suspendedAt, Instant scheduledAt, Instant startedAt,
                          Instant endedAt, int peakViewers, int totalViewers, String vodKey,
                          int vodDurationSeconds,
                          VodStatus vodStatus, boolean isVodPublic) {
        this.sellerId = sellerId;
        this.title = title;
        this.description = description;
        this.liveStatus = liveStatus;
        this.sfuRoomId = sfuRoomId;
        this.egressId = egressId;
        this.hlsUrl = hlsUrl;
        this.hlsArchiveUrl = hlsArchiveUrl;
        this.suspendedReason = suspendedReason;
        this.suspendedAt = suspendedAt;
        this.scheduledAt = scheduledAt;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.peakViewers = peakViewers;
        this.totalViewers = totalViewers;
        this.vodKey = vodKey;
        this.vodDurationSeconds = vodDurationSeconds;
        this.vodStatus = vodStatus;
        this.isVodPublic = isVodPublic;
    }


    public void applyLive(Instant startedAt, String sfuRoomId, String egressId, String hlsUrl){
        this.liveStatus = LiveRoomStatus.LIVE;
        this.startedAt = startedAt;
        this.sfuRoomId = sfuRoomId;
        this.egressId  = egressId;
        this.hlsUrl    = hlsUrl;

    }
    public void applyEnded(Instant endedAt, String hlsArchiveUrl){
        this.liveStatus = LiveRoomStatus.ENDED;
        this.endedAt = endedAt;
        this.hlsArchiveUrl = hlsArchiveUrl;
    }
    public void applySuspended(Instant suspendedAt, String reason){
        this.liveStatus = LiveRoomStatus.SUSPENDED;
        this.suspendedAt = suspendedAt;
        this.suspendedReason = reason;
    }

    public void updateTitle(String title) {
        this.title = title;
    }

    public void updateDescription(String description) {
        this.description = description;
    }

    public void updateScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void updateStreamInfo(String sfuRoomId, String egressId, String hlsUrl) {
        this.sfuRoomId = sfuRoomId;
        this.egressId = egressId;
        this.hlsUrl = hlsUrl;
    }

    public void updateLiveStatus(LiveRoomStatus liveStatus) {
        this.liveStatus = liveStatus;
    }
}
