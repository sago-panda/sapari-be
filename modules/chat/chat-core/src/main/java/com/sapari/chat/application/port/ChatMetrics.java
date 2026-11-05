package com.sapari.chat.application.port;

/**
 * chat 도메인 관측 포트. <b>구현 기술(micrometer)을 이 계층에 들이지 않기 위한 경계다.</b>
 *
 * <p>메서드가 {@code counter} 같은 계측 어휘가 아니라 <b>도메인 사건</b>으로 쓰여 있는 것도 같은
 * 이유다 — 호출부가 "지표를 남긴다" 가 아니라 "무슨 일이 있었는지 알린다" 로 읽혀야 나중에 관측
 * 도구를 바꿔도 도메인 문장이 흔들리지 않는다. 같은 모양이 live 에 이미 있다({@code LiveMetrics}).
 *
 * <p><b>이 포트가 나르는 것은 chat 의 가용성 정책이 실제로 발화했는가다.</b> 이 저장소의 chat 은
 * 실패를 삼키지 않는 대신 <b>방향을 정해</b> 삼킨다 — 모더레이션 조회는 열고, 레이트리밋은 강등하고,
 * 실시간 전달은 포기한다. 그 선택들은 전부 "지금은 집행되지 않는다" 를 뜻하는데, 지금까지 그 사실은
 * 프로세스 안의 카운터와 솎아낸 로그 줄에만 있었다. 즉 <b>정책이 쉬고 있는지를 밖에서 알 수 없었다.</b>
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
     * 아무것도 하지 않는 구현. <b>registry 없이도 뜨는 것이 포트 구현의 기본값이어야 하기 때문</b>이고,
     * 실제 용도는 단위 테스트가 registry 를 세우지 않고 돌게 하는 것이다.
     */
    ChatMetrics NOOP = new ChatMetrics() {
        @Override public void policyNotEnforced(ChatPolicy policy, ChatCheckpoint checkpoint,
                PolicyReadFailure failure) { }
        @Override public void rateLimitDegraded(RateLimitDegradation mode) { }
        @Override public void deliveryDropped(DeliveryStage stage) { }
        @Override public void sessionCut(SessionCutReason reason) { }
        @Override public void dictionaryFastPathLost() { }
    };
}
