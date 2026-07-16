package com.sapari.live.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.LiveStatus.Ended;
import com.sapari.live.domain.model.LiveStatus.Live;
import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.domain.model.LiveStatus.Suspended;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomEntity;
import com.sapari.live.infrastructure.persistence.entity.LiveRoomStatus;

/**
 * LiveRoom 도메인 ↔ LiveRoomEntity 영속 변환 (MapStruct).
 *
 * <p>평면 필드(sellerId/title/description/sellerNickname/thumbnailUrl)만 MapStruct가 자동 매핑한다
 * ({@code toDomain} 직접, {@code toEntity}는 {@code toEntityBase} 경유).
 * sealed {@link LiveStatus}(4-variant) ↔ {@link LiveRoomStatus} enum, variant별 필드, 그리고 엔티티의
 * mutator(applyXxx/updateXxx) 기반 변경은 자동 생성이 불가해 아래 default 메서드가 담당한다.
 *
 * <p>상태 추가 시 {@code toStatus}·{@code toStatusEnum}·{@code applyStatusFields}의 switch를 함께
 * 갱신한다(컴파일러가 강제 — {@code default} 분기 금지).
 *
 * <p>엔티티는 도메인에 대응이 없는 영속 전용 필드(VOD·시청자 집계 등)가 많아 {@code unmappedTargetPolicy=IGNORE}.
 * 상태 관련 컬럼(liveStatus/scheduledAt/sfuRoomId/…)은 mutator로 명시 세팅하므로 자동 매핑 대상이 아니다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface LiveRoomMapper {

    // streamInfo·top-level scheduledAt 은 비워 둔다(상태가 보유) — 기존 동작 보존
    @Mapping(target = "streamInfo", ignore = true)
    @Mapping(target = "scheduledAt", ignore = true)
    @Mapping(target = "streamType", expression = "java(toStreamType(entity))")
    @Mapping(target = "status", expression = "java(toStatus(entity))")
    LiveRoom toDomain(LiveRoomEntity entity);

    /** 평면 필드 + 상태 적용. 상태 컬럼은 toEntityBase에서 비우고, 여기서 mutator로 세팅(빌더가 못 다룸). */
    default LiveRoomEntity toEntity(LiveRoom room) {
        LiveRoomEntity entity = toEntityBase(room);
        applyStatusOnInsert(room, entity);
        applyStreamType(entity, room);
        return entity;
    }

    /** MapStruct가 평면 필드만 채운다. scheduledAt 은 상태에서 세팅하므로 무시. */
    @Mapping(target = "scheduledAt", ignore = true)
    LiveRoomEntity toEntityBase(LiveRoom room);

    /** toEntity 후처리: 상태 enum/scheduledAt 세팅 + variant별 필드 적용. */
    private void applyStatusOnInsert(LiveRoom room, LiveRoomEntity entity) {
        LiveStatus status = room.status();
        entity.updateLiveStatus(toStatusEnum(status));
        if (status instanceof Scheduled scheduled) {
            entity.updateScheduledAt(scheduled.scheduledAt());
        }
        applyStatusFields(entity, status);
    }

    /** 기존 엔티티 in-place 갱신(upsert의 update 경로). 엔티티 mutator 기반이라 MapStruct 자동화 불가. */
    default void updateEntityFromDomain(LiveRoomEntity entity, LiveRoom room) {
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
        applyStreamType(entity, room);

        applyStatusFields(entity, room.status());
        entity.updateLiveStatus(toStatusEnum(room.status()));
    }

    /** enum + variant별 컬럼 → sealed LiveStatus 복원. Ended.hlsArchiveUrl 은 기존대로 hlsUrl 컬럼을 쓴다. */
    default LiveStatus toStatus(LiveRoomEntity entity) {
        return switch (entity.getLiveStatus()) {
            case SCHEDULED -> new Scheduled(entity.getScheduledAt());
            case LIVE -> new Live(
                    entity.getStartedAt(), entity.getSfuRoomId(), entity.getEgressId(), entity.getHlsUrl());
            case ENDED -> new Ended(
                    entity.getStartedAt(), entity.getEndedAt(), entity.getHlsUrl());
            case SUSPENDED -> new Suspended(
                    entity.getStartedAt(), entity.getSuspendedAt(), entity.getSuspendedReason());
        };
    }

    /** streamType 판별자 + ingressId 컬럼 → sealed LiveStreamType 복원(status ↔ enum 과 동일 패턴). */
    default LiveStreamType toStreamType(LiveRoomEntity entity) {
        return switch (entity.getStreamType()) {
            case WEBRTC -> new LiveStreamType.WebRtc();
            case RTMP -> new LiveStreamType.Rtmp(entity.getIngressId());
        };
    }

    /**
     * 도메인 streamType → 엔티티 streamType/ingressId 컬럼(뮤테이터로 판별자↔ingress 항상 동반 세팅).
     * 빌더로 직접 만든 도메인 등 streamType 이 없으면 컬럼을 건드리지 않는다(기존 동작 보존).
     */
    private void applyStreamType(LiveRoomEntity entity, LiveRoom room) {
        LiveStreamType streamType = room.streamType();
        if (streamType == null) {
            return;
        }
        switch (streamType) {
            case LiveStreamType.WebRtc w -> entity.assignWebRtc();
            case LiveStreamType.Rtmp r -> entity.assignRtmpIngress(r.ingressId());
        }
    }

    private LiveRoomStatus toStatusEnum(LiveStatus status) {
        return switch (status) {
            case Scheduled s -> LiveRoomStatus.SCHEDULED;
            case Live l -> LiveRoomStatus.LIVE;
            case Ended e -> LiveRoomStatus.ENDED;
            case Suspended s -> LiveRoomStatus.SUSPENDED;
        };
    }

    private void applyStatusFields(LiveRoomEntity entity, LiveStatus status) {
        switch (status) {
            case Scheduled s -> { }
            case Live l -> entity.applyLive(l.startedAt(), l.sfuRoomId(), l.egressId(), l.hlsUrl());
            case Ended e -> entity.applyEnded(e.endedAt(), e.hlsArchiveUrl());
            case Suspended s -> entity.applySuspended(s.suspendedAt(), s.reason());
        }
    }
}
