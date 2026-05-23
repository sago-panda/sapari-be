package com.sapari.live.domain.repository;

import java.util.List;
import java.util.UUID;

import com.sapari.live.domain.model.LiveRoomCache;

public interface LiveRoomCacheRepository {

    List<LiveRoomCache> findTopByViewers(int limit);
}
