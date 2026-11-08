package com.sapari.chat.application.port;

/**
 * Redis pub/sub 구독 다섯 — 재구독 지표의 태그 값.
 *
 * <p>구독마다 피해 반경이 다르다. 그래서 합치지 않는다 — {@code CHAT_PUBSUB} 가 죽으면 그 파드의
 * <b>모든 채팅 전달과 모든 KICK 알림</b>이 사라지는데 발신자는 ACK 를 받아 아무 이상을 못 느끼고
 * 메시지는 Mongo 에 정상으로 쌓인다. 반면 {@code PROFANITY_DICTIONARY} 가 죽으면 그 파드가 한
 * 주기 늦은 사전으로 집행할 뿐이다. 한 시계열로 묶으면 그 둘이 같은 수에 섞인다.
 */
public enum ChatSubscription {

    /** {@code chat:pubsub:*} — 파드 간 채팅 중계. 죽으면 그 파드의 전달이 통째로 사라진다. */
    CHAT_PUBSUB,

    /** 계정 이벤트(프로필 변경 등). */
    ACCOUNT_EVENT,

    /** 방송 종료 알림 — 놓치면 끝난 방이 글을 받아 준다. */
    LIVE_ROOM_ENDED,

    /** 탈퇴 진행 알림 — 놓치면 탈퇴 중 계정의 세션이 남는다. */
    ACCOUNT_WITHDRAWING,

    /** 금칙어 사전 변경 — 놓치면 한 주기 늦은 사전으로 집행한다. */
    PROFANITY_DICTIONARY
}
