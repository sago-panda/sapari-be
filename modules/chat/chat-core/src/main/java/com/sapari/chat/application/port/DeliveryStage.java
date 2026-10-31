package com.sapari.chat.application.port;

/**
 * 실시간 전달을 포기한 자리. <b>영속은 보장되고 전달만 best-effort</b>라는 계약이 실제로 발화한 지점이다.
 *
 * <p>영속 보장과 짝지어 읽어야 한다 — 여기 값이 올라도 메시지는 Mongo 에 있다. 즉 "유실" 이 아니라
 * "그 순간 화면에 안 떴다" 이고, 이력 조회로 메워진다.
 */
public enum DeliveryStage {

    /**
     * 저장 뒤 Redis 발행이 실패했다. 발신자는 ack 로 확정되지만 <b>다른 Pod 와 같은 Pod 의 다른
     * 세션은 그 메시지를 실시간으로 못 받는다</b>.
     */
    REDIS_PUBLISH,

    /**
     * 세션 sink 발화가 경합 재시도를 소진해 그 세션분을 버렸다. 그 세션 하나만 못 받는다.
     */
    SESSION_EMIT_CONTENTION,

    /**
     * 생존 확인 ping 틱을 백프레셔로 버렸다. 메시지 유실이 아니다 — 다만 이 값이 꾸준히 오르면
     * 그 세션의 아웃바운드가 밀려 있다는 뜻이고, 생존 판정이 늦어진다.
     */
    LIVENESS_PING
}
