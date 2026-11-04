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

    /**
     * 클라 생성 상관관계 id 상한. UUID(36자)를 쓰는 계약이라 여유를 두고도 한참 남는다.
     *
     * <p>여기 있는 이유: 이 값은 <b>거부하는 쪽</b>(전송 서비스)과 <b>에코로 되돌려주는 쪽</b>(WS 핸들러)이
     * 반드시 같아야 한다. 어긋나면 거부는 안 됐는데 에코만 잘리는 구간이 생기고, 그 구간의 클라는
     * 돌려받은 키가 자기가 보낸 것과 달라 낙관적 말풍선을 되돌리지 못한다.
     */
    public static final int MAX_CLIENT_MSG_ID_LENGTH = 64;
}
