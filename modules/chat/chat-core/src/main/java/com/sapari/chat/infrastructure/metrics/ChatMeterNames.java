package com.sapari.chat.infrastructure.metrics;

/**
 * 지표 이름과 태그 키. 문자열이 흩어지면 오타 하나가 조용히 새 시계열을 만든다.
 *
 * <p>이름 규칙은 micrometer 관례를 따른다 — 점으로 구분한 소문자 명사구. registry 가 프로메테우스면
 * {@code chat_policy_open_total} 처럼 자동 변환된다.
 */
final class ChatMeterNames {

    /** 판정을 못 읽어 통과시킨 건수. */
    static final String POLICY_OPEN = "chat.policy.open";

    /** 레이트리밋이 내려앉은 건수. */
    static final String RATE_LIMIT_DEGRADED = "chat.ratelimit.degraded";

    /** 실시간 전달을 포기한 건수. */
    static final String DELIVERY_DROPPED = "chat.delivery.dropped";

    /** 서버가 세션을 끊은 건수. */
    static final String SESSION_CUT = "chat.session.cut";

    static final String TAG_POLICY = "policy";
    static final String TAG_CHECKPOINT = "checkpoint";
    static final String TAG_FAILURE = "failure";
    static final String TAG_MODE = "mode";
    static final String TAG_STAGE = "stage";
    static final String TAG_REASON = "reason";

    private ChatMeterNames() {
    }
}
