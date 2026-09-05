package com.sapari.live.infrastructure.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.PromotionTrigger;
import com.sapari.live.domain.model.LiveStatus;

/**
 * 이 클래스의 유일한 비자명한 주장 — <b>전이·승격은 커밋된 것만 센다</b> — 을 고정한다.
 *
 * <p>이게 틀리면 지표가 조용히 거짓말한다: 롤백된 트랜잭션의 전이가 집계에 남아, 실제로는 시작되지
 * 않은 방송이 "시작됨" 으로 그려진다. 실패해도 아무 데도 티가 나지 않는 종류라 테스트로 못박는다.
 */
class MicrometerLiveMetricsTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");
    private static final LiveStatus READY = new LiveStatus.Ready(T);
    private static final LiveStatus LIVE = new LiveStatus.Live(T, "sfu-1", "eg-1", "https://cdn/x.m3u8");

    private MeterRegistry registry;
    private LiveMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new MicrometerLiveMetrics(registry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("트랜잭션 밖에서는 즉시 집계한다 — 정리 잡은 트랜잭션이 없어 미루면 영영 기록되지 않는다")
    void outsideTransaction_recordsImmediately() {
        metrics.roomTransitioned(READY, LIVE);
        metrics.rtmpPromoted(PromotionTrigger.WEBHOOK);

        assertThat(transitionCount()).isEqualTo(1);
        assertThat(promotionCount(PromotionTrigger.WEBHOOK)).isEqualTo(1);
    }

    @Test
    @DisplayName("트랜잭션 안에서는 커밋 전까지 집계하지 않고, 커밋되면 집계한다")
    void insideTransaction_recordsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.roomTransitioned(READY, LIVE);
        metrics.rtmpPromoted(PromotionTrigger.SELLER_START);

        assertThat(transitionCount()).isZero();
        assertThat(promotionCount(PromotionTrigger.SELLER_START)).isZero();

        commit();

        assertThat(transitionCount()).isEqualTo(1);
        assertThat(promotionCount(PromotionTrigger.SELLER_START)).isEqualTo(1);
    }

    @Test
    @DisplayName("롤백되면 집계하지 않는다 — 일어나지 않은 전이를 세면 지표가 거짓말을 한다")
    void rolledBackTransaction_recordsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        metrics.roomTransitioned(READY, LIVE);
        metrics.rtmpPromoted(PromotionTrigger.RECONCILE);

        rollback();

        assertThat(registry.find("live.room.transition").counter()).isNull();
        assertThat(registry.find("live.rtmp.promotion").counter()).isNull();
    }

    /** 커밋 훅만 실행한다(스프링이 커밋 후에 하는 일과 같은 순서). */
    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        TransactionSynchronizationManager.clearSynchronization();
    }

    /** 롤백은 커밋 훅을 부르지 않는다 — 그게 이 설계가 기대는 유일한 사실이다. */
    private void rollback() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private double transitionCount() {
        var counter = registry.find("live.room.transition").tag("from", "ready").tag("to", "live").counter();
        return counter == null ? 0 : counter.count();
    }

    private double promotionCount(PromotionTrigger trigger) {
        var counter = registry.find("live.rtmp.promotion")
                .tag("trigger", trigger.name().toLowerCase()).counter();
        return counter == null ? 0 : counter.count();
    }
}
