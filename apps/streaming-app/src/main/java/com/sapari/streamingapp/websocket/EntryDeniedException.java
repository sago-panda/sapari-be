package com.sapari.streamingapp.websocket;

/**
 * 입장 게이트가 연결을 거부할 때의 신호. 핸들러가 {@link #reason()}으로 어떤 SYSTEM 메시지(KICKED/BANNED)를
 * 보낼지 정하고 1008 close 한다. (토큰 검증 실패는 {@code WebSocketAuthException}, 이쪽은 토큰은 유효하나
 * chat 소유 모더레이션 상태로 거부되는 경우.)
 */
public class EntryDeniedException extends RuntimeException {

    public enum Reason {
        KICKED,
        BANNED
    }

    private final Reason reason;

    public EntryDeniedException(Reason reason) {
        super("입장 거부: " + reason);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
