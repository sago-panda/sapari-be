package com.sapari.live.application.service;

import java.util.UUID;

/**
 * LiveKit 방 이름 → roomId. 방 이름은 자유 문자열이라 우리 방이 아닐 수 있고, 그런 리소스는
 * 정리 대상에서 제외해야 하므로 예외 대신 {@code null} 을 준다(정리 잡 3종이 공유).
 */
final class LiveKitRoomNames {

    private LiveKitRoomNames() {
    }

    static UUID parseRoomId(String roomName) {
        if (roomName == null || roomName.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(roomName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
