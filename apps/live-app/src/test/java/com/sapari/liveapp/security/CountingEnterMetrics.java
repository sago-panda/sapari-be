package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sapari.live.application.port.EnterPopulation;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.ReconcileLockResult;
import com.sapari.live.domain.model.LiveStatus;

/**
 * 입장 거부만 기록하는 {@link LiveMetrics} 대역 — 나머지 축은 {@code NOOP} 과 같다.
 *
 * <p>상한기 테스트와 필터 테스트가 <b>같은 대역</b>을 쓴다. 각자 익명 클래스를 쓰면 한쪽만
 * 세는 모양이 되고, 실제로 그렇게 전역 버킷 자리의 지표 호출이 분모 0 으로 남았다.
 */
class CountingEnterMetrics implements LiveMetrics {

    final List<EnterPopulation> rejections = new ArrayList<>();

    @Override
    public void enterRateLimited(EnterPopulation population) {
        rejections.add(population);
    }

    @Override
    public void reconcileRoundCompleted(ReconcileJob job, Duration took) {
    }

    @Override
    public void reconcileRoundAborted(ReconcileJob job, ReconcileAbortReason reason) {
    }

    @Override
    public void reconcileRoundFailed(ReconcileJob job) {
    }

    @Override
    public void reconcileLockResult(ReconcileJob job, ReconcileLockResult result) {
    }

    @Override
    public void reconcileActed(ReconcileJob job, ReconcileAction action, int count) {
    }

    @Override
    public void roomTransitioned(LiveStatus from, LiveStatus to) {
    }

    @Override
    public void rtmpPromoted(PromotionTrigger trigger) {
    }

    @Override
    public void liveKitActiveEgressRooms(int rooms) {
    }
}
