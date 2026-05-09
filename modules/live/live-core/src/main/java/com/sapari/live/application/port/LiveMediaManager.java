package com.sapari.live.application.port;

import java.util.UUID;

public interface LiveMediaManager {
    SfuRoomResult createRoom(UUID roomId);
}
