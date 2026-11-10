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
    static final String RECONCILE_LOCK = "live.reconcile.lock";
    static final String MEDIA_CALL = "live.media.call";
    static final String ROOM_TRANSITION = "live.room.transition";
    static final String RTMP_PROMOTION = "live.rtmp.promotion";
    static final String ROOM_ACTIVE = "live.room.active";
    static final String LIVEKIT_EGRESS_ROOMS = "live.livekit.egress.rooms";

    /** 입장 상한이 거부한 건수. 집단은 {@code population} 태그로 가른다(값은 소문자). */
    static final String ENTER_REJECTED = "live.enter.rejected";

    /** 폐기된 토큰의 인증을 거둔 건수. 축은 {@code axis} 태그로 가른다(값은 소문자). */
    static final String TOKEN_REVOKED = "live.token.revoked";

    /** 폐기 여부를 묻지 못한 건수(저장소 장애). 적중과 <b>다른 이름</b>이다 — 대응이 반대다. */
    static final String TOKEN_REVOCATION_FAILED = "live.token.revocation.failed";

    /** 커밋된 전이를 공개 목록 캐시에 반영하지 못한 건수. 쓰기 종류는 {@code write} 태그(register·remove). */
    static final String LIST_CACHE_WRITE_FAILED = "live.list.cache.write.failed";

    /** 목록 재조정이 시청자 수를 읽지 못한 회차 수. */
    static final String LIST_VIEWER_COUNT_UNAVAILABLE = "live.list.viewer-count.unavailable";

    /** 목록 재조정이 방송 중 방을 상한까지만 읽은 회차 수. */
    static final String LIST_REFRESH_SATURATED = "live.list.refresh.saturated";

    /** 목록 재조정 회차가 예외로 끝난 수. */
    static final String LIST_REFRESH_FAILED = "live.list.refresh.failed";

    /** 버린 최근 본 기록 수. 이유는 {@code reason} 태그(rejected·failed). */
    static final String RECENT_VIEW_DROPPED = "live.recent_view.dropped";

    static final String TAG_JOB = "job";
    static final String TAG_WRITE = "write";
    static final String TAG_OUTCOME = "outcome";
    static final String TAG_REASON = "reason";
    static final String TAG_ACTION = "action";
    static final String TAG_OP = "op";
    static final String TAG_RESULT = "result";
    static final String TAG_POPULATION = "population";
    static final String TAG_GATE = "gate";
    static final String TAG_AXIS = "axis";
    static final String TAG_FROM = "from";
    static final String TAG_TO = "to";
    static final String TAG_TRIGGER = "trigger";

    private LiveMeterNames() {
    }
}
