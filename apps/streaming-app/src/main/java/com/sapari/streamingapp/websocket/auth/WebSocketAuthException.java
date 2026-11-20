package com.sapari.streamingapp.websocket.auth;

/**
 * 핸드셰이크 인증 실패 신호. 핸들러가 이 예외를 받으면 WS 연결을 거부(1008 close)한다.
 * 거부 사유 문자열은 서버 로그용 — 와이어로는 고정 close code만 나가고 상세는 싣지 않는다(정보 노출 방지).
 */
public class WebSocketAuthException extends RuntimeException {

    public WebSocketAuthException(String message) {
        super(message);
    }
}
