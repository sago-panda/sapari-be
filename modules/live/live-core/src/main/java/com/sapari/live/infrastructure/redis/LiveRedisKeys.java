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

    /** 방 종료 Pub/Sub 채널 (chat이 구독) — 계약: payload {roomId, endedAt}. */
    static String roomEndedChannel() {
        return "live:room:ended";
    }
}
