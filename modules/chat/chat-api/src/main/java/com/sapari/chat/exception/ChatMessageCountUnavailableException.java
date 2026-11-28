package com.sapari.chat.exception;

/**
 * 채팅 수를 읽지 못했다(chat 저장소 장애). 호출 도메인이 인프라 예외 타입을 알 필요가 없게 번역한 것이다.
 */
public class ChatMessageCountUnavailableException extends RuntimeException {

    public ChatMessageCountUnavailableException(Throwable cause) {
        super("채팅 수를 읽지 못했다", cause);
    }
}
