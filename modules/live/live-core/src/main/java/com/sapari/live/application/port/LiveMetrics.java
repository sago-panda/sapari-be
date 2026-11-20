package com.sapari.live.application.port;

import java.time.Duration;

import com.sapari.live.domain.model.LiveStatus;

/**
 * live 도메인 관측 포트. <b>구현 기술(micrometer)을 이 계층에 들이지 않기 위한 경계다.</b>
 *
 * <p>메서드가 {@code counter}/{@code timer} 같은 계측 어휘가 아니라 <b>도메인 사건</b>으로 쓰여 있는
 * 것도 같은 이유다 — 서비스 코드가 "지표를 남긴다" 가 아니라 "무슨 일이 있었는지 알린다" 로 읽혀야
 * 나중에 관측 도구를 바꿔도 도메인 문장이 흔들리지 않는다.
 *
 * <p><b>구현은 절대 예외를 던지지 않는다.</b> 계측 실패가 방송 시작이나 정리 잡을 깨뜨리면 관측이
 * 장애 원인이 된다 — 관측은 부수효과이지 업무가 아니다.
 */
public interface LiveMetrics {

    /** 정리 잡 한 회차가 끝까지 돌았다. */
    void reconcileRoundCompleted(ReconcileJob job, Duration took);

    /**
     * 정리 잡이 회차를 접었다(가드 발동).
     *
     * <p>완료와 중단을 <b>같은 카운터의 다른 태그</b>가 아니라 별도 메서드로 나눈 건 호출부에서
     * 실수로 빠뜨리기 어렵게 하려는 것이다 — 중단 경로는 {@code return} 한 줄이라 놓치기 쉽다.
     */
    void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason);

    /**
     * 정리 잡 회차가 예외로 끝났다.
     *
     * <p>{@link #reconcileRoundAborted} 와 다르다 — 그쪽은 가드가 <b>스스로</b> 접은 것(판정 근거가
     * 의심스러워 미룸)이고, 이쪽은 <b>깨진 것</b>이다. 밖에서 보면 둘 다 "completed 가 안 오름" 이지만
     * 대응이 정반대라(설정 점검 vs 예외 원인 추적) 반드시 갈라야 한다.
     *
     * <p>이게 없으면 예외로 매 회차 죽는 잡과 스케줄러가 아예 안 도는 상황이 지표상 똑같아진다.
     */
    void reconcileRoundFailed(ReconcileJob job);

    /** 이번 회차에 실제로 한 일. {@code count} 가 0 이어도 호출해도 된다(구현이 무시한다). */
    void reconcileActed(ReconcileJob job, ReconcileAction action, int count);

    /** 방 상태가 전이했다. 커밋된 전이만 세어야 한다 — 구현이 트랜잭션 경계를 본다. */
    void roomTransitioned(LiveStatus from, LiveStatus to);

    /** RTMP 방이 Live 로 승격했다. */
    void rtmpPromoted(PromotionTrigger trigger);

    /**
     * 정리 잡이 방금 관측한 "LiveKit 에서 활성 egress 를 가진 방 수".
     *
     * <p>게이지를 위해 따로 LiveKit 을 부르지 않는다 — 정리 잡이 이미 10분마다 전수 조회를 하므로
     * 그 결과를 얹기만 한다. 해상도는 10분이지만, DB 쪽 활성 방 수와 나란히 두면 두 세계가 벌어지는
     * 구간이 눈으로 보인다. 그게 live 장애의 공통 증상이다.
     */
    void liveKitActiveEgressRooms(int rooms);

    /**
     * 아무것도 하지 않는 구현. <b>단위 테스트가 registry 없이 돌게 하는 것</b>이 실제 용도다.
     *
     * <p>(오해 주의: 지금 {@code live-core} 를 의존하는 앱은 {@code live-app} 하나뿐이다 — 확인함:
     * {@code grep -rn "live-core" --include=*.gradle}. "actuator 없는 다른 앱을 위해" 가 아니라,
     * registry 유무와 무관하게 뜨는 것이 포트 구현의 기본값이어야 하기 때문이다.)
     */
    LiveMetrics NOOP = new LiveMetrics() {
        @Override public void reconcileRoundCompleted(ReconcileJob job, Duration took) { }
        @Override public void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason) { }
        @Override public void reconcileRoundFailed(ReconcileJob job) { }
        @Override public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) { }
        @Override public void roomTransitioned(LiveStatus from, LiveStatus to) { }
        @Override public void rtmpPromoted(PromotionTrigger trigger) { }
        @Override public void liveKitActiveEgressRooms(int rooms) { }
    };
}
