package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.StartLiveView;

@Builder(toBuilder = true)
public record LiveRoom(
        UUID id,
        UUID sellerId,
        String title,
        String description,
        String sellerNickname,
        String thumbnailUrl,
        StreamInfo streamInfo,
        LiveStreamType streamType,
        LiveStatus status,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static LiveRoom create(
            UUID sellerId,
            String title,
            String description,
            String sellerNickname,
            String thumbnailUrl,
            Instant scheduledAt,
            Instant now
    ){
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (sellerNickname == null || sellerNickname.isBlank()) {
            throw new IllegalArgumentException("sellerNickname은 필수입니다.");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt은 필수입니다.");
        }
        return builder()
                .sellerId(sellerId)
                .title(title)
                .description(description)
                .sellerNickname(sellerNickname)
                .thumbnailUrl(thumbnailUrl)
                .status(new Scheduled(scheduledAt))
                .streamType(new LiveStreamType.WebRtc())
                .scheduledAt(scheduledAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public LiveRoom withSfuRoomId(String sfuRoomId) {
        //streamInfo 정보 없는 경우 sfuRoomId만 추가, 그 외에는 기존 값 복사하고 sfuRoomId만 교체
        StreamInfo updated = (this.streamInfo == null) ?
                StreamInfo.ofSfuRoomId(sfuRoomId) : StreamInfo.of(sfuRoomId, this.streamInfo.egressId(), this.streamInfo.hlsUrl());
        return toBuilder()
                .streamInfo(updated)
                .build();
    }

    public LiveRoom startLive(StreamInfo newStreamInfo, Instant now){
        if(!canStartLive()){
            throw new InvalidLiveStateException(this.id != null ? this.id.toString() : "알 수 없는 방");
        }

        var nextStatus = new LiveStatus.Live(
                now,
                newStreamInfo.sfuRoomId(),
                newStreamInfo.egressId(),
                newStreamInfo.hlsUrl()
        );
        return toBuilder()
                .streamInfo(newStreamInfo)
                .status(nextStatus)
                .updatedAt(now)
                .build();
    }

    /**
     * RTMP 방송 시작 대기(Ready)로 전이한다 — 판매자가 방송 시작(상품 등록)을 눌렀으나 OBS가 아직 미연결인 단계.
     * Scheduled 에서만 허용하며, OBS 연결 webhook(또는 시작 시점 ingress 활성 확인)이 {@link #goLiveFromReady}로
     * Live 전이를 마무리한다. WebRTC 방은 이 단계 없이 곧바로 {@link #startLive}로 Live 가 된다.
     */
    public LiveRoom arm(Instant now){
        if (!(this.status instanceof LiveStatus.Scheduled scheduled)) {
            throw new InvalidLiveStateException(this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        return toBuilder()
                .status(new LiveStatus.Ready(scheduled.scheduledAt()))
                .updatedAt(now)
                .build();
    }

    /**
     * RTMP 방송 시작 대기(Ready) → Live 전이. OBS가 ingress 에 연결됐을 때(webhook 또는 시작 시점 확인) 호출한다.
     * 이미 sfuRoomId 가 배정된 방(예약 시 createRoom)을 전제로, egress 정보를 실은 새 StreamInfo 로 Live 를 연다.
     */
    public LiveRoom goLiveFromReady(StreamInfo newStreamInfo, Instant now){
        if (!canGoLiveByRtmp()) {
            throw new InvalidLiveStateException(this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        var nextStatus = new LiveStatus.Live(
                now,
                newStreamInfo.sfuRoomId(),
                newStreamInfo.egressId(),
                newStreamInfo.hlsUrl()
        );
        return toBuilder()
                .streamInfo(newStreamInfo)
                .status(nextStatus)
                .updatedAt(now)
                .build();
    }

    public LiveRoom endLive(Instant now){
        if (!canEndLive()) {
            throw new InvalidLiveStateException(this.id != null ? this.id.toString() : "알 수 없는 방");
        }

        Instant startedAt = switch (this.status) {
            case LiveStatus.Live l -> l.startedAt();
            case LiveStatus.Suspended s -> s.startedAt(); // 방송 전 정지면 null, 방송 후 정지면 startedAt
            default -> throw new IllegalStateException("예상치 못한 상태: " + this.status);
        };

        String hlsArchiveUrl = (streamInfo != null) ? streamInfo.hlsUrl() : null;
        var endedStatus = new LiveStatus.Ended(startedAt, now, hlsArchiveUrl);

        return toBuilder()
                .status(endedStatus)
                .updatedAt(now)
                .build();
    }

    // RTMP ingress 배정은 도메인 전이가 아니라 조건부 UPDATE 다(RtmpIngressAssigner) — 스냅샷 읽기 위에서는
    // 배타성을 보장할 수 없어 DB 가 승자를 고른다. 여기에 assignRtmpIngress 를 되살리지 말 것:
    // `ingress_id IS NULL` 조건 없는 경로가 생겨 한 방에 유효한 streamKey 가 여러 개 나간다.

    /**
     * OBS가 끝내 연결되지 않아 Ready 상태에 갇힌 방을 종료한다.
     * Ready 상태에서만 허용되고 만료 시간 판정은 배치 정책이므로 여기서 검사하지 않는다.
     */
    public LiveRoom expire(Instant now){
        if(!canExpire()){
            throw new InvalidLiveStateException(this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        LiveStatus status = new LiveStatus.Ended(null, now, null);
        return toBuilder()
                .status(status)
                .updatedAt(now)
                .build();
    }

    public String sfuRoomId(){
        return streamInfo.sfuRoomId();
    }

    public String egressId(){
        return streamInfo.egressId();
    }

    public String hlsUrl(){
        return streamInfo.hlsUrl();
    }

    public boolean canStartLive(){
        return status instanceof LiveStatus.Scheduled;
    }

    public boolean canEnterLive(){
        return status instanceof LiveStatus.Live;
    }

    public boolean canEndLive(){
        return status instanceof LiveStatus.Live || status instanceof LiveStatus.Suspended;
    }

    /** RTMP OBS 연결 시 Ready → Live 전이 가능 여부. Ready 이고 RTMP 송출인 방만 해당(멱등 가드). */
    public boolean canGoLiveByRtmp(){
        return status instanceof LiveStatus.Ready && isRtmp();
    }

    public boolean canPrepareIngress(){
        return status instanceof LiveStatus.Scheduled;
    }

    public boolean canExpire(){
        return status instanceof LiveStatus.Ready;
    }

    public boolean isRtmp(){
        return streamType instanceof LiveStreamType.Rtmp;
    }

    /**
     * 주어진 ingressId 가 이 방에 배정된 것인지. webhook 이 실어 온 id 를 전이 전에 대조하는 용도라
     * 알 수 없는 값(null/blank)은 <b>거짓</b>이다 — 모르면 전이하지 않는 쪽이 안전하다.
     */
    public boolean hasIngress(String ingressId){
        return streamType instanceof LiveStreamType.Rtmp rtmp
                && ingressId != null && !ingressId.isBlank()
                && rtmp.ingressId().equals(ingressId);
    }

    public CreateLiveView toCreateLiveView(){
        return new CreateLiveView(id, title, description);
    }

    public StartLiveView toStartLiveResult(String sfuToken, String sfuUrl){
        return new StartLiveView(
                id.toString(),
                sfuToken,
                hlsUrl(),
                sfuUrl
        );
    }
}
