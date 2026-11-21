package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.domain.exception.InvalidLiveRoomException;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveErrorCode;
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
        // 방별 다시보기 공개 스위치(live_rooms.is_vod_public, DB 기본 true). 읽기 전용이다 —
        // 엔티티 빌더에 이 필드가 없고 update 경로도 건드리지 않으므로 앱이 값을 덮어쓸 수 없다.
        boolean vodPublic,
        // 다시보기 처리 상태(live_rooms.vod_status). 이 레코드로는 읽기 전용 — LiveRoomRepository 의
        // 조건부 UPDATE 두 개(웹훅 advanceVodStatus, 복구 recoverStuckReplays)만 쓴다. 다시보기 노출은 READY 만.
        VodStatus vodStatus,
        // RTMP 송출 주소(live_rooms.rtmp_url). 발급의 조건부 UPDATE 만 쓴다(RtmpIngressAssigner) — 읽기 전용.
        // 자격증명이 아니다: 키(streamKey) 없이는 송출할 수 없고, 키는 저장하지 않는다.
        String rtmpUrl,
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
            LiveStreamType streamType,
            Instant now
    ){
        if (sellerId == null) {
            throw new IllegalArgumentException("sellerId는 필수입니다.");
        }
        // 송출 방식은 생성 때 고정된다. 요청 DTO 가 먼저 막고, CreateLiveService 경로는 그 전에 switch 에서 NPE 로 끝난다 — 여기는 다른 호출자용 방어선이다.
        if (streamType == null) {
            throw new IllegalArgumentException("streamType은 필수입니다.");
        }
        requireEditableFields(title, scheduledAt, now);
        if (sellerNickname == null || sellerNickname.isBlank()) {
            throw new IllegalArgumentException("sellerNickname은 필수입니다.");
        }
        return builder()
                .sellerId(sellerId)
                .title(title)
                .description(normalizeDescription(description))
                .sellerNickname(sellerNickname)
                .thumbnailUrl(thumbnailUrl)
                .status(new Scheduled(scheduledAt))
                .streamType(streamType)
                .vodPublic(true)
                .vodStatus(VodStatus.NONE)
                .scheduledAt(scheduledAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 생성과 수정이 함께 지키는 필드 규칙. 둘이 갈리지 않게 한곳에 둔다.
     *
     * <p>예정 시각은 현재보다 뒤여야 한다(같으면 거부) — 판매자 입력이므로 IAE 가 아니라 LIVE-015.
     * null 은 요청 DTO 가 먼저 막으므로 여기 오면 서버 결함이라 IAE 로 남긴다.
     */
    private static void requireEditableFields(String title, Instant scheduledAt, Instant now) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title은 필수입니다.");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("scheduledAt은 필수입니다.");
        }
        if (!scheduledAt.isAfter(now)) {
            throw new InvalidLiveRoomException(LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE,
                    LiveErrorCode.LIVE_SCHEDULE_NOT_FUTURE.getMessage());
        }
    }

    /** 설명은 선택 항목이다 — 공백뿐이면 없는 것으로 저장한다. */
    private static String normalizeDescription(String description) {
        return (description == null || description.isBlank()) ? null : description;
    }

    public LiveRoom withSfuRoomId(String sfuRoomId) {
        //streamInfo 정보 없는 경우 sfuRoomId만 추가, 그 외에는 기존 값 복사하고 sfuRoomId만 교체
        StreamInfo updated = (this.streamInfo == null) ?
                StreamInfo.ofSfuRoomId(sfuRoomId) : StreamInfo.of(sfuRoomId, this.streamInfo.egressId(), this.streamInfo.hlsUrl(), this.streamInfo.hlsArchiveUrl());
        return toBuilder()
                .streamInfo(updated)
                .build();
    }

    public LiveRoom startLive(StreamInfo newStreamInfo, Instant now){
        if(!canStartLive()){
            throw new InvalidLiveStateException(LiveErrorCode.INVALID_LIVE_STATE, this.id != null ? this.id.toString() : "알 수 없는 방");
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
            throw new InvalidLiveStateException(LiveErrorCode.INVALID_LIVE_STATE, this.id != null ? this.id.toString() : "알 수 없는 방");
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
            throw new InvalidLiveStateException(LiveErrorCode.INVALID_LIVE_STATE, this.id != null ? this.id.toString() : "알 수 없는 방");
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
            throw new InvalidLiveStateException(LiveErrorCode.LIVE_END_NOT_ALLOWED, this.id != null ? this.id.toString() : "알 수 없는 방");
        }

        Instant startedAt = switch (this.status) {
            case LiveStatus.Live l -> l.startedAt();
            case LiveStatus.Suspended s -> s.startedAt(); // 방송 전 정지면 null, 방송 후 정지면 startedAt
            default -> throw new IllegalStateException("예상치 못한 상태: " + this.status);
        };

        String hlsArchiveUrl = (streamInfo != null) ? streamInfo.hlsArchiveUrl() : null;
        var endedStatus = new LiveStatus.Ended(startedAt, now, hlsArchiveUrl);

        return toBuilder()
                .status(endedStatus)
                .updatedAt(now)
                .build();
    }

    /**
     * 관리자가 방송 중인 방을 끊는다(관리자 종료). 결과는 Ended 가 아니라 Cancelled 라 다시보기가 생기지 않는다.
     * 대상과 거부 코드는 판매자 종료와 같다(Live·Suspended, 아니면 LIVE-009). 방송했던 방이므로 startedAt 을
     * 보존하고, RoomEnded 발행은 호출자 책임이다(채팅 세션이 열려 있다).
     */
    public LiveRoom cancelByAdmin(Instant now){
        if (!canEndLive()) {
            throw new InvalidLiveStateException(LiveErrorCode.LIVE_END_NOT_ALLOWED, this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        // 나머지는 위 canEndLive 가 막는다. default 를 두지 않아 상태가 늘면 컴파일이 알려 준다.
        Instant startedAt = switch (this.status) {
            case LiveStatus.Live l -> l.startedAt();
            case LiveStatus.Suspended s -> s.startedAt();
            case LiveStatus.Scheduled s -> throw new IllegalStateException("예상치 못한 상태: " + s);
            case LiveStatus.Ready r -> throw new IllegalStateException("예상치 못한 상태: " + r);
            case LiveStatus.Ended e -> throw new IllegalStateException("예상치 못한 상태: " + e);
            case LiveStatus.Cancelled c -> throw new IllegalStateException("예상치 못한 상태: " + c);
        };
        return toBuilder()
                .status(new LiveStatus.Cancelled(this.scheduledAt, startedAt, now))
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
            throw new InvalidLiveStateException(LiveErrorCode.LIVE_END_NOT_ALLOWED, this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        LiveStatus status = new LiveStatus.Ended(null, now, null);
        return toBuilder()
                .status(status)
                .updatedAt(now)
                .build();
    }

    /**
     * 판매자가 예약을 취소한다. Scheduled 에서만 허용한다 — Ready 는 OBS 연결 webhook 과 경합하므로
     * 만료 배치에 맡긴다. 방송된 적이 없으므로 RoomEnded 를 발행하지 않는다(호출자 책임).
     */
    public LiveRoom cancel(Instant now){
        if (!canCancel()) {
            throw new InvalidLiveStateException(LiveErrorCode.CANCEL_NOT_ALLOWED, this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        return toBuilder()
                .status(new LiveStatus.Cancelled(((LiveStatus.Scheduled) this.status).scheduledAt(), now))
                .updatedAt(now)
                .build();
    }

    /** 예약 내용을 통째로 바꾼다. Scheduled 에서만 허용한다. 예정 시각은 상태에 담겨야 영속에 반영된다. */
    public LiveRoom update(String title, String description, String thumbnailUrl, Instant scheduledAt, Instant now){
        if (!canUpdate()) {
            throw new InvalidLiveStateException(LiveErrorCode.UPDATE_NOT_ALLOWED, this.id != null ? this.id.toString() : "알 수 없는 방");
        }
        requireEditableFields(title, scheduledAt, now);
        return toBuilder()
                .title(title)
                .description(normalizeDescription(description))
                .thumbnailUrl(thumbnailUrl)
                .status(new LiveStatus.Scheduled(scheduledAt))
                .scheduledAt(scheduledAt)
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

    public boolean canCancel(){
        return status instanceof LiveStatus.Scheduled;
    }

    public boolean canUpdate(){
        return status instanceof LiveStatus.Scheduled;
    }

    public boolean canExpire(){
        return status instanceof LiveStatus.Ready;
    }

    public boolean isRtmp(){
        return streamType instanceof LiveStreamType.Rtmp;
    }

    /** 송출 키(ingress)를 이미 발급받은 RTMP 방인지. RTMP 방은 키 발급 전이면 false 다. */
    public boolean hasIngressKey(){
        return streamType instanceof LiveStreamType.Rtmp rtmp && rtmp.ingressId() != null;
    }

    /**
     * 주어진 ingressId 가 이 방에 배정된 것인지. webhook 이 실어 온 id 를 전이 전에 대조하는 용도라
     * 알 수 없는 값(null/blank)은 <b>거짓</b>이다 — 모르면 전이하지 않는 쪽이 안전하다.
     */
    public boolean hasIngress(String ingressId){
        return streamType instanceof LiveStreamType.Rtmp rtmp
                && ingressId != null && !ingressId.isBlank()
                && ingressId.equals(rtmp.ingressId());
    }

    public CreateLiveView toCreateLiveView(){
        return new CreateLiveView(id, title, description);
    }

    public StartLiveView toStartLiveResult(String sfuToken, String sfuUrl){
        return new StartLiveView(
                id,
                sfuToken,
                hlsUrl(),
                sfuUrl
        );
    }
}
