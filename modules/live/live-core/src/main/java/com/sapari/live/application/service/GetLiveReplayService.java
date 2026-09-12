package com.sapari.live.application.service;

import lombok.RequiredArgsConstructor;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sapari.live.domain.exception.LiveReplayNotFoundException;
import com.sapari.live.domain.model.LiveRoom;
import com.sapari.live.domain.model.LiveStatus;
import com.sapari.live.domain.repository.LiveRoomRepository;
import com.sapari.live.port.GetLiveReplayUseCase;
import com.sapari.live.view.ReplayView;

@Service
@RequiredArgsConstructor
public class GetLiveReplayService implements GetLiveReplayUseCase {

    private final LiveRoomRepository liveRoomRepository;

    @Override
    @Transactional(readOnly = true)
    public ReplayView getReplay(UUID roomId) {
        LiveRoom room = liveRoomRepository.findById(roomId)
                .orElseThrow(() -> new LiveReplayNotFoundException(roomId.toString()));
        if (!(room.status() instanceof LiveStatus.Ended ended)
                || ended.hlsArchiveUrl() == null || ended.hlsArchiveUrl().isBlank()) {
            throw new LiveReplayNotFoundException(roomId.toString());
        }
        return new ReplayView(room.id(), ended.hlsArchiveUrl());
    }
}
