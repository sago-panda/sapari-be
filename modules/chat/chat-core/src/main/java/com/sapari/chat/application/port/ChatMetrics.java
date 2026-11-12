package com.sapari.chat.application.port;

/**
 * chat 도메인 관측 포트. <b>구현 기술(micrometer)을 이 계층에 들이지 않기 위한 경계다.</b>
 *
 * <p>메서드가 {@code counter} 같은 계측 어휘가 아니라 <b>도메인 사건</b>으로 쓰여 있는 것도 같은
 * 이유다 — 호출부가 "지표를 남긴다" 가 아니라 "무슨 일이 있었는지 알린다" 로 읽혀야 나중에 관측
 * 도구를 바꿔도 도메인 문장이 흔들리지 않는다. 같은 모양이 live 에 이미 있다({@code LiveMetrics}).
 *
 * <p><b>이 포트가 나르는 것은 chat 의 가용성 정책이 실제로 발화했는가다.</b> 이 저장소의 chat 은
 * 실패를 삼키지 않는 대신 <b>방향을 정해</b> 삼킨다 — 입장 판정은 열고, 전송 판정은 거절하고,
 * 레이트리밋은 강등하고, 실시간 전달은 포기한다. 거절을 뺀 셋은 "지금은 집행되지 않는다" 를 뜻하고,
 * 거절은 "지금은 받지 않는다" 를 뜻한다. 지금까지 그 사실은 프로세스 안의 카운터와 솎아낸 로그 줄에만
 * 있었다. 즉 <b>정책이 쉬고 있는지를 밖에서 알 수 없었다.</b>
 *
 * <p><b>구현은 절대 예외를 던지지 않는다.</b> 이건 취향이 아니라 업무 계약이다 — 이 포트의 호출부
 * 대부분이 {@code onErrorResume} 람다 안, 즉 fail-open 분기다. 거기서 던지면 Reactor 가 하류로
 * 전파해 <b>통과시키기로 한 요청이 거부된다</b> — 가용성 우선 정책이 관측 때문에 정반대로 뒤집힌다.
 * 그 불변식은 {@code SendChatService} 가 이미 주석으로 경고해 둔 것이고, 테스트가 고정한다.
 */
public interface ChatMetrics {

    /**
     * 판정을 못 읽어 <b>통과시켰다</b>.
     *
     * <p>태그 셋이 전부 필요하다 — 무엇이(policy) 어디서(checkpoint) 왜(failure) 열렸는지가 각각
     * 다른 대응으로 이어진다. 특히 {@code failure} 는 재시도로 낫는지를 가르므로, 합치면 영영 안 낫는
     * 상태가 곧 복구될 장애에 묻힌다.
     */
    void policyNotEnforced(ChatPolicy policy, ChatCheckpoint checkpoint, PolicyReadFailure failure);

    /**
     * 전송 판정을 못 읽어 <b>그 전송을 거절했다</b>. {@link #policyNotEnforced} 의 반대 방향이다 —
     * 그쪽은 통과시켰고 이쪽은 막았다. 거절된 전송은 저장되지 않으므로 발신자가 같은 키로 다시 보낸다.
     */
    void sendRefused(ChatPolicy policy);

    /** 레이트리밋이 완전한 판정을 못 하고 내려앉았다. 집행은 계속된다 — 정확도만 잃는다. */
    void rateLimitDegraded(RateLimitDegradation mode);

    /** 실시간 전달을 포기했다. 메시지는 영속돼 있다 — 그 순간 화면에 안 떴을 뿐이다. */
    void deliveryDropped(DeliveryStage stage);

    /** 서버가 세션을 끊었다. 와이어에서 구분되지 않는 close 1000 셋을 여기서만 가를 수 있다. */
    void sessionCut(SessionCutReason reason);

    /**
     * 사전 전파의 빠른 길을 잃었다 — 이 Pod 은 재시작까지 주기 확인만으로 돈다.
     *
     * <p>로그 한 줄로는 부족하다. 그 줄을 놓치면 이 Pod 은 "최대 한 주기 늦은 사전으로 집행하는
     * Pod" 으로 남고, 함대 일부만 그 상태면 증상이 "어떤 Pod 은 막고 어떤 Pod 은 안 막는다" 다.
     */
    void dictionaryFastPathLost();

    /**
     * 연산 상한이 영속을 끊었다. <b>갈래가 필요하다</b> — {@link PersistStage} 가 그 이유를 적는다.
     *
     * <p>{@link #deliveryDropped} 와 다른 축이다. 그쪽은 "영속됐고 전달만 잃었다" 이고 이쪽은
     * 영속 자체가 끊긴 축이다. 발신자에게는 에러가 가므로(ack 아님) 사용자는 알지만, 규모는
     * 이 축으로만 보인다.
     */
    void persistTimedOut(PersistStage stage);

    /**
     * pub/sub 구독이 <b>재구독을 시도했다</b>.
     *
     * <p><b>이 메서드가 생기기 전까지</b> 이 축의 지표가 0건이었다 — 그때
     * {@code ChatMeterNames} 와 {@code ChatSessionGaugeConfig} 중 구독을 보는 것이
     * 하나도 없었다(이 이름이 그 첫 번째다). 신호는 최대 30초마다 남는
     * {@code log.warn} 한 줄뿐이었고, 그 줄을 놓치면 <b>구독이 죽은 파드가 Ready 로 서 있는</b>
     * 상태를 밖에서 알 수 없다.
     *
     * <p><b>이 카운터의 증가율이 "끊겼다 복구" 와 "영구히 막혔다" 를 가른다.</b> 재구독은
     * {@code retryWhen} 루프이므로 복구되면 몇 번 오르고 멈추고, 막히면 끝없이 오른다.
     * 실측(SPR-179): 운영 모양에서 {@code PSUBSCRIBE} 확립 응답을 상한 넘게 지연시켰을 때
     * 재시도 2회 뒤 복구되어 수신 1건이었다 — 즉 영구 사망이 기본값은 아니다.
     * 그래서 필요한 것은 "지금 살아 있나" 가 아니라 <b>이 수가 멈추는가</b>다.
     *
     * <p>⚠️ 이 미터가 못 보는 갈래는 상류가 조용히 {@code onComplete} 로 끝나는 쪽이다 —
     * {@code retryWhen} 이 에러만 받으므로 재시도도 로그도 미터도 없이 영구히 멈춘다. 분모 5/5
     * (어댑터 전부 {@code doOnComplete}·{@code repeat} 0건). 자세한 수는 {@code ChatMeterNames}.
     *
     * <p>⚠️ 호출부가 {@code doBeforeRetry} 람다 안이다 — 이 포트의 "절대 던지지 않는다" 계약이
     * 거기서도 그대로 걸린다. 던지면 자가복구 루프가 그 자리에서 끝난다.
     */
    void subscriptionResubscribed(ChatSubscription subscription);

    /**
     * 탈퇴 신청 기록을 쓰지 못했다 — 그 사람은 탈퇴 전에 받아 둔 룸 토큰이 만료될 때까지 다시 들어올 수
     * 있다. 끊기는 그대로 진행되므로 사용자 쪽 증상이 없고, 이 수가 유일한 신호다.
     */
    void withdrawalRecordFailed();

    /**
     * 세션당 인바운드 상한을 넘은 프레임을 읽지 않고 버렸다. 클라에게는 아무것도 돌아가지 않는다.
     * 정상 클라가 닿지 않으므로 기대값은 0 이고, 오르면 그 파드에 회선 속도로 미는 세션이 있다.
     * <b>어느 세션인지는 이 지표로 알 수 없다</b> — 태그도 로그도 없다.
     */
    void inboundFrameDropped();

    /**
     * 아무것도 하지 않는 구현. <b>registry 없이도 뜨는 것이 포트 구현의 기본값이어야 하기 때문</b>이고,
     * 실제 용도는 단위 테스트가 registry 를 세우지 않고 돌게 하는 것이다.
     */
    ChatMetrics NOOP = new ChatMetrics() {
        @Override public void policyNotEnforced(ChatPolicy policy, ChatCheckpoint checkpoint,
                PolicyReadFailure failure) { }
        @Override public void sendRefused(ChatPolicy policy) { }
        @Override public void rateLimitDegraded(RateLimitDegradation mode) { }
        @Override public void deliveryDropped(DeliveryStage stage) { }
        @Override public void sessionCut(SessionCutReason reason) { }
        @Override public void dictionaryFastPathLost() { }
        @Override public void persistTimedOut(PersistStage stage) { }
        @Override public void subscriptionResubscribed(ChatSubscription subscription) { }
        @Override public void withdrawalRecordFailed() { }
        @Override public void inboundFrameDropped() { }
    };
}
