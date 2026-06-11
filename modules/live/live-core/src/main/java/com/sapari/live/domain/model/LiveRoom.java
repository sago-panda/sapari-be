package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.EnterLiveView;
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

    public CreateLiveView toCreateLiveView(){
        return new CreateLiveView(id, title, description);
    }

    public EnterLiveView toEnterLiveResult(){
        return new EnterLiveView(hlsUrl());
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
