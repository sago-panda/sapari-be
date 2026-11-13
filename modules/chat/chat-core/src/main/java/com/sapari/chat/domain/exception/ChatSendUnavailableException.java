package com.sapari.chat.domain.exception;

/**
 * 전송 판정(강퇴·밴·방 종료)을 읽지 못해 이번 전송을 받지 않았다.
 *
 * <p>저장 전에 던지므로 <b>아무것도 남지 않았다</b> — 같은 {@code clientMsgId} 로 다시 보내는 것이
 * 안전하다. 서버 쪽 상태라 5xx({@link ChatErrorCode#SEND_UNAVAILABLE})다.
 */
public class ChatSendUnavailableException extends ChatException {

    public ChatSendUnavailableException(Throwable cause) {
        super(ChatErrorCode.SEND_UNAVAILABLE, "전송 판정을 읽지 못해 전송을 거절했다", cause);
    }
}
