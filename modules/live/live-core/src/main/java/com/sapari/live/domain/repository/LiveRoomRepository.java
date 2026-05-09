package com.sapari.live.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.sapari.live.domain.model.LiveRoom;

public interface LiveRoomRepository {
    LiveRoom save(LiveRoom liveRoom);
    Optional<LiveRoom> findById(UUID id);
}
