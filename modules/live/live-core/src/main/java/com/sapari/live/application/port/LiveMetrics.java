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

    /** 스케줄러 프록시가 시도한 분산 락의 결과. */
    void reconcileLockResult(ReconcileJob job, ReconcileLockResult result);

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
     * 입장 상한이 요청 하나를 거부했다.
     *
     * <p><b>로그만으로는 부족해서 있다.</b> 거부 로그는 집단당 10초에 한 줄로 솎이므로 규모를
     * 못 준다 — 그리고 규모가 바로 "공격인가, 한도가 낮은가" 를 가르는 축이다. 한도는 정상
     * 트래픽에서 역산한 값이 아니라 CPU 천장에서 역산한 값이므로
     * ({@code EnterRateLimitProperties} javadoc) 그 판정을 지표 없이 할 수 없다.
     *
     * <p>집단 태그가 필요한 이유: 게스트 거부와 회원 거부는 다른 사건이다. 전자는 대체로
     * 정상 상태에서도 생길 수 있고 후자는 로그인 사용자가 막히고 있다는 뜻이다.
     *
     * <p>관문 태그가 필요한 이유는 같은 모양이고 <b>한 단계 더 안쪽</b>이다 —
     * {@link EnterRejectionGate#SHARD} 는 한 계정을 찾으라는 신호이고
     * {@link EnterRejectionGate#GLOBAL} 은 한도를 올리라는 신호다. 두 경로가 같은 지표·같은
     * 로그를 쓰면 그 둘이 합쳐져 당직자가 반대 방향의 대응을 고를 수 있다.
     */
    void enterRateLimited(EnterPopulation population, EnterRejectionGate gate);

    /**
     * 폐기된 access 토큰의 인증을 거뒀다.
     *
     * <p><b>이게 없으면 폐기 조회가 한 번이라도 작동했는지 알 수 없다.</b> 적중은 거부 응답을
     * 만들지 않는다 — 공개 경로에서는 그냥 게스트로 내려가고 역할 요구 경로에서만 401 이 된다.
     * 즉 바깥에서 보면 "폐기 조회가 꺼진 것" 과 "폐기가 한 건도 없는 것" 이 똑같다.
     *
     * <p>축 태그의 이유는 {@link TokenRevocationAxis} 에 있다.
     */
    void tokenRevocationHit(TokenRevocationAxis axis);

    /**
     * 폐기 여부를 <b>묻지 못했다</b>(저장소 장애).
     *
     * <p>{@link #tokenRevocationHit} 과 <b>같은 카운터의 다른 태그가 아니라 별도 메서드</b>다 —
     * 적중은 보안 사건이고 이쪽은 인프라 사건이라 대응이 정반대다(그 계정을 보라 vs Redis 를
     * 보라). {@link #reconcileRoundAborted} 와 {@link #reconcileRoundFailed} 를 가른 것과 같다.
     *
     * <p>그리고 이쪽만 <b>볼륨이 장애 규모에 비례</b>한다. 그래서 로그는 10초에 한 줄로 솎고
     * 규모는 이 수로 본다 — 형제 {@code EnterRateLimitFilter} 가 거부마다의 WARN 을 피한 것과
     * 같은 이유다(그쪽 실측: 거부 50건에 핸들러 WARN 50줄).
     */
    void tokenRevocationLookupFailed();

    /**
     * 룸 토큰 한 장을 발급했다 — <b>서명을 쳤는지 캐시에서 나왔는지</b>를 함께 적는다.
     *
     * <p>이 수가 필요한 이유는 캐시가 <b>헛돌 때 아무 신호가 없다</b>는 것이다. 적중률이 0 이어도
     * 응답·처리량·로그가 전부 같고 차이는 CPU 에만 난다. 그 상태는 "서명 비용을 걷어냈다" 는
     * 문장만 남기고 비용은 그대로인 모양이라, 다음 사람이 그 문장을 근거로 상한을 올린다.
     *
     * <p>두 값을 <b>한 카운터의 태그</b>로 둔 것은 {@link #tokenRevocationHit}/
     * {@link #tokenRevocationLookupFailed} 를 갈라 둔 것과 반대 판단이고, 근거는 둘의 관계다 —
     * 여기선 합이 <b>회원 발급 전수</b>이고 구하려는 값이 비율이라 같은 이름이어야 나눌 수 있다.
     * 저쪽은 합에 의미가 없고(보안 사건 + 인프라 사건) 대응이 정반대라 갈랐다.
     *
     * <p>게스트 발급은 <b>세지 않는다</b> — 캐시를 타지 않으므로 분모에 넣으면 적중률이 구조적으로
     * 눌려 "슬롯이 작다" 와 구분되지 않는다({@link RoomTokenSignatureSource}).
     */
    void roomTokenIssued(RoomTokenSignatureSource source);

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
        @Override public void reconcileLockResult(ReconcileJob job, ReconcileLockResult result) { }
        @Override public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) { }
        @Override public void roomTransitioned(LiveStatus from, LiveStatus to) { }
        @Override public void rtmpPromoted(PromotionTrigger trigger) { }
        @Override public void liveKitActiveEgressRooms(int rooms) { }
        @Override public void enterRateLimited(
                EnterPopulation population, EnterRejectionGate gate) { }
        @Override public void tokenRevocationHit(TokenRevocationAxis axis) { }
        @Override public void tokenRevocationLookupFailed() { }
        @Override public void roomTokenIssued(RoomTokenSignatureSource source) { }
    };
}
