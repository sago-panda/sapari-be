package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.command.EnterLiveCommand;
import com.sapari.live.domain.exception.InvalidLiveStateException;
import com.sapari.live.domain.exception.LiveNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.EnterLiveFacade;
import com.sapari.live.view.EnterLiveResult;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnterLiveService implements EnterLiveFacade {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public EnterLiveResult execute(EnterLiveCommand command) {
        LiveRoom room = liveRoomRepository.findById(command.roomId())
                .orElseThrow(() -> new LiveNotFoundException(command.roomId().toString()));

        if (!(room.status() instanceof LiveStatus.Live)) {
            throw new InvalidLiveStateException(command.roomId().toString());
        }

        log.info("roomId: {} hlsUrl 발급", command.roomId());

        return new EnterLiveResult(
                room.streamInfo().hlsUrl()
        );
    }
}
