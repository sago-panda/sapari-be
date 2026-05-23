package com.sapari.live.infrastructure.redis;

import java.util.UUID;

final class LiveRedisKeys {

    private LiveRedisKeys() {}

    static String room(UUID roomId) {
        return "live:room:" + roomId;
    }

    static String ranking() {
        return "live:ranking";
    }
}
