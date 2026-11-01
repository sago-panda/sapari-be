package com.sapari.chat.application.port;

/**
 * 판정을 못 읽은 <b>이유</b>. 재시도로 낫는가가 갈린다 — 대응이 정반대라 절대 합치지 않는다.
 */
public enum PolicyReadFailure {

    /**
     * 조회가 실패했다(접속 불가·상한 초과 등). Redis 가 돌아오면 저절로 낫는다.
     */
    UNAVAILABLE,

    /**
     * 키 타입이 어긋났다. <b>재시도해도, Redis 가 되살아나도 낫지 않는다</b> — 사람이 그 키를
     * 치울 때까지 그 방의 판정은 계속 열려 있다. 서버가 스스로 지우지 않는 것은 의도다(명단 전체 손실).
     */
    STORE_CORRUPTED
}
