package com.sapari.live.domain.model;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import com.sapari.live.domain.model.LiveStatus.Scheduled;
import com.sapari.live.view.CreateLiveView;
import com.sapari.live.view.EnterLiveResult;
import com.sapari.live.view.StartLiveResult;

@Builder(toBuilder = true)
public record LiveRoom(
        UUID id,
        UUID sellerId,
        String title,
        String description,
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
            Instant scheduledAt,
            Instant now
    ){
        return builder()
                .sellerId(sellerId)
                .title(title)
                .description(description)
                .status(new Scheduled(scheduledAt))
                .scheduledAt(scheduledAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public LiveRoom withSfuRoomId(String sfuRoomId) {
        //streamInfo 정보 없는 경우 sfuRoomId만 추가, 그 외에는 기존 값 복사하고 sfuRoomId만 교체
        StreamInfo updated = (this.streamInfo == null) ?
                new StreamInfo(sfuRoomId, null, null) : new StreamInfo(sfuRoomId, this.streamInfo.egressId(), this.streamInfo.hlsUrl());
        return toBuilder()
                .streamInfo(updated)
                .build();
    }

    public LiveRoom startLive(StreamInfo newStreamInfo, Instant now){
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
        //status 변경
        LiveStatus.Live live = (LiveStatus.Live) this.status;
        var endedStatus = new LiveStatus.Ended(
                live.startedAt(),
                now,
                streamInfo().hlsUrl()
        );

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

    public EnterLiveResult toEnterLiveResult(){
        return new EnterLiveResult(hlsUrl());
    }

    public StartLiveResult toStartLiveResult(String sfuToken, String sfuUrl){
        return new StartLiveResult(
                id.toString(),
                sfuToken,
                hlsUrl(),
                sfuUrl
        );
    }
}
