package com.sapari.chat.application.protocol;

/**
 * SYSTEM OutboundMessage의 code 값. 텍스트는 클라이언트가 code로 렌더하므로(서버는 displayMessage 안 실음)
 * 코드 문자열을 흩뿌리지 않도록 여기서 고정한다.
 */
public enum SystemMessageCode {
    KICKED,
    ROOM_ENDED,
    BANNED,

    /**
     * 같은 사람이 같은 방에 동시 세션을 너무 많이 열었다 — 입장 거부(close 1008).
     *
     * <p>클라 문구가 강퇴·밴과 달라야 한다. 이쪽은 제재가 아니라 "다른 탭/기기를 닫으면 들어올 수
     * 있다" 이고, 그 차이를 아는 유일한 수단이 이 code 다(close 코드는 셋 다 1008 이다).
     */
    TOO_MANY_SESSIONS
}
