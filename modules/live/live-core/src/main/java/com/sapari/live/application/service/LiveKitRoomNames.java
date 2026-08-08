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
        UUID parsed;
        try {
            parsed = UUID.fromString(roomName);
        } catch (IllegalArgumentException e) {
            return null;
        }
        // 정규 표기(소문자 36자)만 우리 방으로 본다. UUID.fromString 은 대문자·축약형("1-2-3-4-5")도 받아
        // <b>다른 문자열로 정규화</b>하는데, 그러면 남의 방 이름이 우리 roomId 의 변형일 때 DB 조회는 우리
        // 방으로 매칭되고 정리는 그 이름으로 나간다(= 남의 리소스를 지운다). 우리가 만드는 이름은 전부
        // roomId.toString() 이라 정규 표기다.
        return parsed.toString().equals(roomName) ? parsed : null;
    }
}
