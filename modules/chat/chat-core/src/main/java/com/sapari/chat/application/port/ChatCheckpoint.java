package com.sapari.chat.application.port;

/**
 * 판정이 걸리는 자리. 같은 판정이 두 번 불리고 <b>잃는 것이 다르다</b>.
 *
 * <p>입장에서 열리면 제재받은 사람이 방에 들어오고, 전송에서 열리면 이미 들어온 사람이 글을 쓴다.
 * 입장만 열렸다면 그 사람은 조용히 보고만 있다 — 그래서 둘을 나눠야 영향 범위를 읽을 수 있다.
 */
public enum ChatCheckpoint {

    /** WS 핸드셰이크 직후 입장 게이트. */
    ENTRY,

    /** 메시지 전송 경로의 재확인. */
    SEND
}
