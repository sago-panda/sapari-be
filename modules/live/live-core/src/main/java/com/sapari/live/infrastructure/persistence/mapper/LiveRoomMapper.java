package com.sapari.live.infrastructure.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.model.LiveStreamType;
import com.sapari.live.domain.model.LiveStatus.Ended;
import com.sapari.live.domain.model.LiveStatus.Live;
import com.sapari.live.domain.model.LiveStatus.Ready;
import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.domain.model.LiveStatus.Suspended;
import com.sapari.live.domain.model.StreamInfo;
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
 * 상태 관련 컬럼(liveStatus/scheduledAt/sfuRoomId/…)은 쓰기 방향에서 mutator로 명시 세팅하므로 자동 매핑
 * 대상이 아니다. 읽기 방향은 {@code toStatus}/{@code toStreamInfo}가 같은 컬럼을 도메인으로 되돌린다.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface LiveRoomMapper {

    // top-level scheduledAt 은 비워 둔다(상태가 보유). streamInfo 는 컬럼에서 복원한다.
    @Mapping(target = "streamInfo", expression = "java(toStreamInfo(entity))")
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

    /**
     * stream 컬럼 → {@link StreamInfo}. sfuRoomId 가 비면 <b>null</b> 이다 —
     * {@code StreamInfo} 는 sfuRoomId 를 필수로 검증하는데, 예약 저장과 {@code createRoom} 사이엔
     * sfu_room_id 가 비어 있는 행이 정상적으로 존재한다. 여기서 던지면 그 방을 읽을 수조차 없다.
     * egressId·hlsUrl 은 Ready 방에서 비는 게 정상이라 그대로 넘긴다.
     */
    default StreamInfo toStreamInfo(LiveRoomEntity entity) {
        String sfuRoomId = entity.getSfuRoomId();
        if (sfuRoomId == null || sfuRoomId.isBlank()) {
            return null;
        }
        return StreamInfo.of(sfuRoomId, entity.getEgressId(), entity.getHlsUrl());
    }

    /** MapStruct가 평면 필드만 채운다. scheduledAt 은 상태에서 세팅하므로 무시. */
    @Mapping(target = "scheduledAt", ignore = true)
    LiveRoomEntity toEntityBase(LiveRoom room);

    /** toEntity 후처리: 상태 enum/scheduledAt 세팅 + variant별 필드 적용. */
    private void applyStatusOnInsert(LiveRoom room, LiveRoomEntity entity) {
        LiveStatus status = room.status();
        entity.updateLiveStatus(toStatusEnum(status));
        applyScheduledAt(entity, status);
        applyStatusFields(entity, status);
    }

    /** 기존 엔티티 in-place 갱신(upsert의 update 경로). 엔티티 mutator 기반이라 MapStruct 자동화 불가. */
    default void updateEntityFromDomain(LiveRoomEntity entity, LiveRoom room) {
        entity.updateTitle(room.title());
        entity.updateDescription(room.description());
        entity.updateSellerNickname(room.sellerNickname());
        entity.updateThumbnailUrl(room.thumbnailUrl());
        // scheduledAt 은 top-level 이 아니라 상태(Scheduled/Ready)에만 담긴다(toDomain 이 top-level 을 비움).
        // room.scheduledAt() 을 그대로 쓰면 되저장 시 scheduled_at 컬럼이 null 로 지워지므로 상태 기반으로 세팅한다.
        applyScheduledAt(entity, room.status());

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
            case READY -> new Ready(entity.getScheduledAt());
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
            case Ready r -> LiveRoomStatus.READY;
            case Live l -> LiveRoomStatus.LIVE;
            case Ended e -> LiveRoomStatus.ENDED;
            case Suspended s -> LiveRoomStatus.SUSPENDED;
        };
    }

    /** Scheduled/Ready 는 scheduled_at 컬럼을 상태에서 세팅. 그 외 상태는 컬럼을 건드리지 않아 기존 값을 보존한다. */
    private void applyScheduledAt(LiveRoomEntity entity, LiveStatus status) {
        switch (status) {
            case Scheduled s -> entity.updateScheduledAt(s.scheduledAt());
            case Ready r -> entity.updateScheduledAt(r.scheduledAt());
            case Live l -> { }
            case Ended e -> { }
            case Suspended s -> { }
        }
    }

    private void applyStatusFields(LiveRoomEntity entity, LiveStatus status) {
        switch (status) {
            case Scheduled s -> { }
            case Ready r -> { }
            case Live l -> entity.applyLive(l.startedAt(), l.sfuRoomId(), l.egressId(), l.hlsUrl());
            case Ended e -> entity.applyEnded(e.endedAt(), e.hlsArchiveUrl());
            case Suspended s -> entity.applySuspended(s.suspendedAt(), s.reason());
        }
    }
}
