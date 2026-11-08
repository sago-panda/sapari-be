package com.sapari.chat.application.port;

/**
 * 연산 상한이 영속을 끊은 자리. <b>두 갈래가 사용자에게 뜻하는 것이 정반대라 한 수로 세면 안 된다.</b>
 *
 * <p>{@link DeliveryStage} 와 짝지어 읽지 말 것 — 그쪽은 "영속됐고 전달만 잃었다" 이고 이쪽은
 * 영속 자체가 끊긴 축이다.
 */
public enum PersistStage {

    /**
     * 메시지 저장이 끊겼다 — 그 메시지는 <b>아무 데도 남지 않았다</b>.
     *
     * <p>발신자에게는 에러가 가므로(ack 아님) 사용자는 안다. 규모는 이 값으로만 보인다.
     */
    WRITE,

    /**
     * {@code DuplicateKey} 뒤 기존 메시지를 되읽다 끊겼다 — 그 문서는 <b>이미 있다</b>.
     *
     * <p>{@link #WRITE} 와 섞으면 이력에 남아 있는 메시지를 유실로 보고한다. 발신자가 받는 것은
     * 번역되지 않은 5xx 이고, 다시 보내면 같은 {@code clientMsgId} 라 또 이 갈래로 온다 — 즉 이
     * 값이 오르는 동안 그 사용자는 <b>이미 저장된 자기 메시지를 확인할 수 없다</b>.
     */
    DUPLICATE_RECOVERY_READ
}
