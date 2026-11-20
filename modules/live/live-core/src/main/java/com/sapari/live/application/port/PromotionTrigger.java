package com.sapari.live.application.port;

/**
 * RTMP 방을 Live 로 올린 주체.
 *
 * <p>승격 결과는 어느 쪽이든 "Live" 라 겉으로 같다. 그런데 판매자 체감은 전혀 다르다 —
 * {@link #WEBHOOK} 은 OBS 연결 즉시, {@link #RECONCILE} 은 최대 10분 뒤다. 라이브 커머스에서
 * 10분 지연은 그 방송을 통째로 날린다. 경로를 나눠 세지 않으면 이 지연이 지표에 보이지 않는다.
 *
 * <p>승격 진입점이 늘면 여기에 값을 추가해야 하고, 그때 컴파일러가 호출부를 짚어준다 —
 * 호출자 쪽에서 각자 지표를 남기는 방식이었다면 새 경로가 조용히 통계에서 빠졌을 것이다.
 */
public enum PromotionTrigger {
    /** LiveKit {@code ingress_started} webhook — OBS 가 나중에 도착한 정상 경로 */
    WEBHOOK,
    /** 판매자가 시작을 누른 순간 OBS 가 이미 붙어 있던 랑데부({@code StartLiveService}) */
    SELLER_START,
    /** Ready 고착 정리 잡 — 놓친 랑데부를 뒤늦게 완성한 경로(= 실시간 감지가 샜다는 뜻) */
    RECONCILE
}
