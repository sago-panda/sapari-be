package com.sapari.chat.domain.model;

import java.util.UUID;

/**
 * chat 도메인 공유 상수.
 */
public final class ChatConstants {

    private ChatConstants() {
    }

    /**
     * SYSTEM 메시지 발신 주체의 고정 UUID. 실제 유저가 아닌 서버 발신 신호(강퇴·종료·밴 등)에 쓴다.
     * 리터럴이 여러 곳에 흩어지면 한 글자 오타로 식별이 어긋나므로 이 상수만 사용한다.
     */
    public static final UUID SYSTEM_SENDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
}
