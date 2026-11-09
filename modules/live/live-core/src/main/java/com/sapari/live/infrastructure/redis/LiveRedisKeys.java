package com.sapari.live.infrastructure.redis;

import java.util.UUID;

final class LiveRedisKeys {

    private LiveRedisKeys() {}

    static String room(UUID roomId) {
        return rawRoom(roomId.toString());
    }

    /** 랭킹 구성원 문자열 그대로 — 정리 대상은 UUID 로 읽히지 않는 잔재일 수도 있다. */
    static String rawRoom(String roomId) {
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
