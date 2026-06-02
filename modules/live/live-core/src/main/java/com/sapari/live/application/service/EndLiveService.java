package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.global.time.TimeProvider;
import com.sapari.live.application.port.LiveMediaManager;
import com.sapari.live.command.EndLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EndLiveUseCase;

@Slf4j
@Service
@RequiredArgsConstructor
public class EndLiveService implements EndLiveUseCase {

    private final LiveRoomRepository liveRoomRepository;
    private final LiveMediaManager liveMediaManager;
    private final TimeProvider timeProvider;

    @Override
    @Transactional
    public void end(EndLiveCommand command){
        LiveRoom room = liveRoomRepository.findByIdAndSellerId(command.roomId(), command.sellerId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        // 외부 호출 전 상태 사전 검증
        if (!room.canEndLive()) {
            throw new InvalidLiveStateException(room.id().toString());
        }

        if (room.streamInfo() != null) {
            liveMediaManager.stopHlsEgress(command.roomId(), room.egressId());
            liveMediaManager.closeRoom(room.sfuRoomId());
        }

        LiveRoom endedRoom = room.endLive(timeProvider.now());

        liveRoomRepository.save(endedRoom);
    }
}
