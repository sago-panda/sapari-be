package com.sapari.live.infrastructure.media;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels.Room;
import livekit.LivekitRoom.CreateRoomRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.application.port.SfuRoomResult;
import com.sapari.live.domain.exception.LiveMediaException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveKitMediaManager implements LiveMediaManager {

    private final RoomServiceClient roomServiceClient;
    private static final int EMPTY_TIMEOUT = 300;
    private static final int MAX_PARTICIPANTS = 1000;

    @Override
    public SfuRoomResult createRoom(UUID roomId){

        try{
            Room room = roomServiceClient.createRoom(
                    roomId.toString(),
                    EMPTY_TIMEOUT,
                    MAX_PARTICIPANTS
            ).execute().body();

            log.info("LiveKit 룸 생성 완료: roomId={}", roomId);
            return new SfuRoomResult(room.getName(), room.getMaxParticipants(), true);
        } catch (IOException e){
            log.error("LiveKit 룸 생성 실패: roomId={}", roomId, e);
            throw new LiveMediaException("SFU 룸 생성 실패: " + roomId, e);
        }
    }
}
