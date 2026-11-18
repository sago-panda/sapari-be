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

import com.sapari.storage.db.entity.UuidTimeEntity;

@Entity
@Getter
@Table(name = "live_rooms", schema = "live_schema")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveRoomEntity extends UuidTimeEntity {

    @Column(nullable = false)
    private UUID sellerId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, length = 50)
    private String sellerNickname;

    @Column(length = 500)
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LiveRoomStatus liveStatus;

    // --- 송출: WEBRTC=토큰 publish / RTMP=LiveKit Ingress ---
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StreamType streamType = StreamType.WEBRTC;

    private String sfuRoomId;

    // RTMP Ingress 참조. streamKey(자격증명)는 저장하지 않음 — 발급 시 1회 전달, 재조회는 LiveKit(listIngress).
    private String ingressId;

    // RTMP 송출 주소. ingressId 와 같은 조건부 UPDATE 로만 쓴다(assignRtmpIngressIfAbsent) — 빌더에 없다.
    // streamKey 와 달리 자격증명이 아니라 저장한다(키 없이는 송출할 수 없다).
    @Column(length = 500)
    private String rtmpUrl;

    // --- 시청 (HLS Egress, 공통) ---
    private String egressId;

    private String hlsUrl;

    private String hlsArchiveUrl;

    // --- 일정/운영 ---
    private Instant scheduledAt;

    private Instant scheduledEndAt;

    @Column(nullable = false)
    private int maxDurationSeconds = 7200;

    @Column(nullable = false)
    private int disconnectGraceSeconds = 300;

    private Instant startedAt;

    private Instant endedAt;

    private Instant deactivateAfter;

    // --- 정지 (코드 상태머신 유지) ---
    @Column(columnDefinition = "TEXT")
    private String suspendedReason;

    private Instant suspendedAt;

    // --- 집계 캐시 ---
    @Column(nullable = false)
    private int peakViewers = 0;

    @Column(nullable = false)
    private int concurrentViewers = 0;

    @Column(nullable = false)
    private int uniqueViewers = 0;

    @Column(nullable = false)
    private int chatCount = 0;

    @Column(nullable = false)
    private boolean chatDisabled = false;

    @Column(nullable = false)
    private int likeCount = 0;

    // --- VOD ---
    @Column(length = 500)
    private String vodKey;

    private Integer vodDurationSeconds;

    // vod_status·vod_failed_reason 은 조건부 UPDATE 두 개(웹훅 advanceVodStatus, 복구 recoverStuckReplays)만 쓰고, 둘 다 NONE/PROCESSING 에서만 옮긴다.
    // 엔티티는 변경 필드가 하나만 있어도 모든 컬럼을 UPDATE 하므로, 쓰기 가능하면 그 전에 읽어 둔 낡은 값으로 결과를
    // 되돌린다.
    // 삽입 값은 DB 기본값('NONE')이 채운다. @DynamicUpdate 가 없어서 생기는 일이므로 이 매핑을 "고치지" 말 것.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, insertable = false, updatable = false)
    private VodStatus vodStatus = VodStatus.NONE;

    @Column(columnDefinition = "TEXT", insertable = false, updatable = false)
    private String vodFailedReason;

    @Column(nullable = false)
    private boolean isVodPublic = true;

    @Builder
    public LiveRoomEntity(UUID sellerId, String title, String description, String sellerNickname,
                          String thumbnailUrl, LiveRoomStatus liveStatus,
                          String sfuRoomId, String egressId, String hlsUrl, String hlsArchiveUrl,
                          String suspendedReason, Instant suspendedAt, Instant scheduledAt,
                          Instant startedAt, Instant endedAt) {
        this.sellerId = sellerId;
        this.title = title;
        this.description = description;
        this.sellerNickname = sellerNickname;
        this.thumbnailUrl = thumbnailUrl;
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
    /** 취소 시각은 종단 시각 컬럼(ended_at)에 둔다 — 집계·다시보기는 status=ENDED 로 거르므로 섞이지 않는다. */
    public void applyCancelled(Instant cancelledAt){
        this.liveStatus = LiveRoomStatus.CANCELLED;
        this.endedAt = cancelledAt;
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

    public void updateSellerNickname(String sellerNickname) {
        this.sellerNickname = sellerNickname;
    }

    public void updateThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public void updateScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public void updateHlsArchiveUrl(String hlsArchiveUrl) {
        this.hlsArchiveUrl = hlsArchiveUrl;
    }

    public void updateStreamInfo(String sfuRoomId, String egressId, String hlsUrl) {
        this.sfuRoomId = sfuRoomId;
        this.egressId = egressId;
        this.hlsUrl = hlsUrl;
    }

    /**
     * WEBRTC 송출로 설정 — ingress 컬럼을 함께 비워 판별자↔ingress 불일치를 차단.
     */
    public void assignWebRtc() {
        this.streamType = StreamType.WEBRTC;
        this.ingressId = null;
    }

    /**
     *  RTMP Ingress 자격 배정 — 판별자(RTMP)와 ingress 컬럼을 항상 함께 세팅.
     */
    public void assignRtmpIngress(String ingressId) {
        this.streamType = StreamType.RTMP;
        this.ingressId = ingressId;
    }

    public void updateLiveStatus(LiveRoomStatus liveStatus) {
        this.liveStatus = liveStatus;
    }
}
