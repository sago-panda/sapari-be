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

    /** 사전 전파의 빠른 길을 잃은 건수. */
    static final String DICTIONARY_FAST_PATH_LOST = "chat.dictionary.fastpath.lost";

    /**
     * 연산 상한이 영속을 끊었다. 태그는 {@link #TAG_STAGE} 하나이고 {@code PersistStage} 가 그 값이다.
     *
     * <p>⚠️ <b>두 값의 확신 수준이 달라서 합치면 안 된다.</b> {@code stage=write} 는 <b>결과를 알 수
     * 없다</b>(문서가 있을 수도 없을 수도 있다 — 클라이언트 상한은 서버 쓰기를 취소하지 않는다.
     * 실측: 상한 3ms·5ms 에서 예외인데 문서 1건, 1ms·2ms 에서는 0건, <b>두 갈래가 같은 예외
     * 클래스</b>). {@code stage=duplicate_recovery_read} 는 전제가 {@code DuplicateKey} 라 문서가
     * <b>이미 있다</b>. 합치면 "존재 확정" 을 "불명" 에 섞어 둘 다 못 읽는 수가 된다.
     *
     * <p>⚠️ <b>{@code write} 를 데이터 유실 확정으로 경보하지 않는다.</b> 한때 이 자리와
     * {@code infra/AGENTS.md} 런북이 그렇게 적었고 거짓이었다. 유실일 수도 있으니 경보 자체는
     * 유효하지만, 당직자가 받는 문장은 "유실" 이 아니라 <b>"결과 미확인 — 확인이 필요하다"</b> 다.
     *
     * <p>⚠️ {@link #DELIVERY_DROPPED} 에도 태우지 않는다. 그 미터의 정의가 "메시지는 영속됐고 실시간
     * 전달만 잃었다" 이고 {@code DeliveryStage} 네 값이 모두 그 전제다 — 쓰기 실패를 거기 더하면
     * 그 정의가 거짓이 되고, 당직자가 "영속은 됐으니 이력에 있다" 로 읽는다.
     */
    static final String PERSIST_TIMEOUT = "chat.persist.timeout";

    /**
     * pub/sub 구독이 재구독을 시도한 건수. 태그는 {@code subscription} 하나다.
     *
     * <p>⚠️ <b>증가율로 읽는다.</b> 재구독은 {@code retryWhen} 루프이므로 끊김 후 복구되면 몇 번
     * 오르고 멈추고, 막히면 끝없이 오른다 — 누적값이 아니라 "멈추는가" 가 신호다.
     * 그래서 0 은 "건강" 이 아니라 "이번 창에 재구독이 없었다" 다.
     *
     * <p>⚠️ <b>이 미터가 못 보는 갈래는 "구독이 안 섰다" 가 아니다</b> — 그쪽은 보인다.
     * {@code autoConnect(0)} 이 조립 시점에 업스트림을 연결하므로 부팅에 Redis 가 없어도 재시도
     * 루프가 돌고 이 수가 오른다(실측: 하류 구독자 0 에서도 400ms 에 10회).
     * <b>못 보는 것은 상류가 조용히 {@code onComplete} 로 끝나는 갈래다</b> — 재시도 0회 · 수신 0 ·
     * 로그도 미터도 침묵 · 영구다. {@code retryWhen} 은 에러만 받고 완료는 그대로 통과시킨다.
     * 분모는 <b>5/5</b> 다 — 다섯 어댑터 전부 {@code doOnComplete}·{@code repeat} 가 0건이다.
     * 닫는 모양은 종단 신호이고(완료에 미터·로그를 달거나 완료를 에러로 바꿔 재시도 루프가 소유),
     * <b>마지막 수신 시각 게이지로는 안 닫힌다</b> — 조용한 방과 죽은 구독이 같은 출력이다.
     */
    static final String SUBSCRIPTION_RESUBSCRIBED = "chat.subscription.resubscribed";

    /** 탈퇴 신청 기록을 쓰지 못한 건수. */
    static final String WITHDRAWAL_RECORD_FAILED = "chat.withdrawal.record.failed";

    static final String TAG_POLICY = "policy";
    static final String TAG_CHECKPOINT = "checkpoint";
    static final String TAG_FAILURE = "failure";
    static final String TAG_MODE = "mode";
    static final String TAG_STAGE = "stage";
    static final String TAG_REASON = "reason";
    static final String TAG_SUBSCRIPTION = "subscription";

    private ChatMeterNames() {
    }
}
