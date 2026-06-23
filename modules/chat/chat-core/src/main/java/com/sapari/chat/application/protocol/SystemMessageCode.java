package com.sapari.chat.application.protocol;

/**
 * SYSTEM OutboundMessage의 code 값. 텍스트는 클라이언트가 code로 렌더하므로(서버는 displayMessage 안 실음)
 * 코드 문자열을 흩뿌리지 않도록 여기서 고정한다.
 */
public enum SystemMessageCode {
    KICKED,
    ROOM_ENDED,
    BANNED
}
