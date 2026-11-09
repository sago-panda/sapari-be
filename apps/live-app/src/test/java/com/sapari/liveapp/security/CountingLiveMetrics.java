package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.sapari.live.application.port.EnterPopulation;
import com.sapari.live.application.port.EnterRejectionGate;
import com.sapari.live.application.port.ListCacheWrite;
import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.application.port.ReconcileAbortReason;
import com.sapari.live.application.port.ReconcileAction;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.ReconcileLockResult;
import com.sapari.live.application.port.TokenRevocationAxis;
import com.sapari.live.domain.model.LiveStatus;

/**
 * 이 패키지의 보안 필터들이 내는 지표를 기록하는 {@link LiveMetrics} 대역 — 나머지 축은
 * {@code NOOP} 과 같다.
 *
 * <p><b>축마다 대역을 따로 만들지 않는다.</b> 한때 상한기 테스트와 필터 테스트가 각자 익명
 * 클래스를 썼고, 그래서 전역 버킷 자리의 지표 호출이 분모 0 으로 남았다. 폐기 조회가 축을
 * 하나 더 내면서 이름에서 {@code Enter} 를 뗐다 — 이름이 한 축을 가리키면 다음 축이 또 자기
 * 대역을 만든다.
 */
class CountingLiveMetrics implements LiveMetrics {

    final List<EnterPopulation> rejections = new ArrayList<>();
    final List<EnterRejectionGate> gates = new ArrayList<>();
    final List<TokenRevocationAxis> revocationHits = new ArrayList<>();
    int revocationLookupFailures;

    @Override
    public void enterRateLimited(EnterPopulation population, EnterRejectionGate gate) {
        rejections.add(population);
        gates.add(gate);
    }

    @Override
    public void tokenRevocationHit(TokenRevocationAxis axis) {
        revocationHits.add(axis);
    }

    @Override
    public void tokenRevocationLookupFailed() {
        revocationLookupFailures++;
    }

    @Override
    public void listCacheWriteFailed(ListCacheWrite write) {
    }

    @Override
    public void listViewerCountUnavailable() {
    }

    @Override
    public void listRefreshSaturated() {
    }

    @Override
    public void listRefreshFailed() {
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
