package com.sapari.live.application.port;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sapari.live.domain.model.LiveStatus;

/**
 * 기록만 하는 {@link LiveMetrics} 테스트 대역.
 *
 * <p>Mockito 목이 아니라 손으로 쓴 대역인 이유: 지표는 "무엇이 몇 번 불렸나" 보다 <b>어떤 갈래로
 * 세었나</b>가 중요해서, 검증이 {@code verify(...)} 나열이 아니라 결과 목록에 대한 단언이어야 읽힌다.
 */
public class RecordingLiveMetrics implements LiveMetrics {

    public final List<ReconcileJob> completedRounds = new ArrayList<>();
    public final List<ReconcileAbortReason> abortedRounds = new ArrayList<>();
    public final List<ReconcileJob> failedRounds = new ArrayList<>();
    public final List<String> acted = new ArrayList<>();
    public final List<String> transitions = new ArrayList<>();
    public final List<PromotionTrigger> promotions = new ArrayList<>();
    public int liveKitEgressRooms = -1;

    @Override
    public void reconcileRoundCompleted(ReconcileJob job, Duration took) {
        completedRounds.add(job);
    }

    @Override
    public void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason) {
        abortedRounds.add(reason);
    }

    @Override
    public void reconcileRoundFailed(ReconcileJob job) {
        failedRounds.add(job);
    }

    @Override
    public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) {
        // 0 도 기록한다 — 운영 구현이 0 을 기록하기 때문이다(시계열이 없는 것과 0 은 다르게 읽힌다).
        // 대역이 0 을 버리면 "0 으로 기록함" 과 "아예 호출 안 함" 이 테스트에서 같아 보인다.
        acted.add(action + "=" + count);
    }

    @Override
    public void roomTransitioned(LiveStatus from, LiveStatus to) {
        // null 을 허용한다 — 운영 구현(MicrometerLiveMetrics.statusTag)이 허용하므로, 대역이 더 좁으면
        // 테스트만 NPE 로 죽고 운영에서는 멀쩡한 상황이 생긴다. 대역은 계약보다 좁아선 안 된다.
        transitions.add(name(from) + "->" + name(to));
    }

    private static String name(LiveStatus status) {
        return status == null ? "none" : status.getClass().getSimpleName();
    }

    @Override
    public void rtmpPromoted(PromotionTrigger trigger) {
        promotions.add(trigger);
    }

    @Override
    public void liveKitActiveEgressRooms(int rooms) {
        liveKitEgressRooms = rooms;
    }
}
