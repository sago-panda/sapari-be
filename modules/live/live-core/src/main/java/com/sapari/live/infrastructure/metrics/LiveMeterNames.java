package com.sapari.live.infrastructure.metrics;

/**
 * 지표 이름과 태그 키. 문자열이 흩어지면 오타 하나가 조용히 새 시계열을 만든다.
 *
 * <p>이름 규칙은 micrometer 관례를 따른다 — 점으로 구분한 소문자 명사구. registry 가 프로메테우스면
 * {@code live_reconcile_round_total} 처럼 자동 변환된다.
 */
final class LiveMeterNames {

    static final String RECONCILE_ROUND = "live.reconcile.round";
    static final String RECONCILE_DURATION = "live.reconcile.duration";
    static final String RECONCILE_ACTED = "live.reconcile.acted";
    static final String MEDIA_CALL = "live.media.call";
    static final String ROOM_TRANSITION = "live.room.transition";
    static final String RTMP_PROMOTION = "live.rtmp.promotion";
    static final String ROOM_ACTIVE = "live.room.active";
    static final String LIVEKIT_EGRESS_ROOMS = "live.livekit.egress.rooms";

    static final String TAG_JOB = "job";
    static final String TAG_OUTCOME = "outcome";
    static final String TAG_REASON = "reason";
    static final String TAG_ACTION = "action";
    static final String TAG_OP = "op";
    static final String TAG_RESULT = "result";
    static final String TAG_FROM = "from";
    static final String TAG_TO = "to";
    static final String TAG_TRIGGER = "trigger";

    private LiveMeterNames() {
    }
}
