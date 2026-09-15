package com.sapari.liveapp.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.ReconcileLockResult;

class TrackingLockProviderTest {

    @Test
    void unterminatedSchedulerIsInterruptedButLocksAreLeftToLeaseExpiry() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);

        SchedulingConfig.ReconcileTaskScheduler.finishShutdown(executor);

        // 아직 돌고 있는 회차에는 중단 신호만 — 락은 lock-at-most-for 만료가 인계한다.
        verify(executor).shutdownNow();
    }

    @Test
    void schedulerResolvesLockProviderAtShutdownAfterBeanRegistration() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        SchedulingConfig.ReconcileTaskScheduler scheduler = new SchedulingConfig.ReconcileTaskScheduler(
                beanFactory.getBeanProvider(TrackingLockProvider.class));
        TrackingLockProvider lockProvider = mock(TrackingLockProvider.class);
        beanFactory.registerSingleton("lockProvider", lockProvider);
        scheduler.initialize();

        scheduler.shutdown();

        verify(lockProvider).beginShutdown();
    }

    private static final LockConfiguration CONFIG = new LockConfiguration(
            Instant.now(), "live-reconcile-orphan-media", Duration.ofMinutes(15), Duration.ZERO);

    @Test
    void acquiredLockIsRecordedAndUnlockedOnlyByItsOwnRound() {
        AtomicInteger unlocks = new AtomicInteger();
        SimpleLock lock = unlocks::incrementAndGet;
        LiveMetrics metrics = mock(LiveMetrics.class);
        TrackingLockProvider provider = new TrackingLockProvider(ignored -> Optional.of(lock), metrics);

        SimpleLock acquired = provider.lock(CONFIG).orElseThrow();
        // 종료가 시작돼도 이미 회차가 쥔 락은 건드리지 않는다 — 반납은 회차 자신(ShedLock finally)의 몫.
        provider.beginShutdown();

        assertThat(unlocks).hasValue(0);
        acquired.unlock();
        assertThat(unlocks).hasValue(1);
        verify(metrics).reconcileLockResult(ReconcileJob.ORPHAN_MEDIA, ReconcileLockResult.ACQUIRED);
    }

    @Test
    void lockAcquiredWhileShuttingDownIsHandedBackImmediately() {
        AtomicInteger unlocks = new AtomicInteger();
        LiveMetrics metrics = mock(LiveMetrics.class);
        AtomicReference<TrackingLockProvider> self = new AtomicReference<>();
        TrackingLockProvider provider = new TrackingLockProvider(configuration -> {
            self.get().beginShutdown(); // 락을 잡는 사이에 종료가 시작된 창을 재현한다
            return Optional.of(unlocks::incrementAndGet);
        }, metrics);
        self.set(provider);

        assertThat(provider.lock(CONFIG)).isEmpty();
        assertThat(unlocks).hasValue(1);
        verify(metrics).reconcileLockResult(ReconcileJob.ORPHAN_MEDIA, ReconcileLockResult.SHUTDOWN);
    }

    @Test
    void skippedAndFailedAttemptsAreRecorded() {
        LiveMetrics skippedMetrics = mock(LiveMetrics.class);
        TrackingLockProvider skipped = new TrackingLockProvider(ignored -> Optional.empty(), skippedMetrics);

        assertThat(skipped.lock(CONFIG)).isEmpty();
        verify(skippedMetrics).reconcileLockResult(ReconcileJob.ORPHAN_MEDIA, ReconcileLockResult.SKIPPED);

        LiveMetrics failedMetrics = mock(LiveMetrics.class);
        LockProvider failing = ignored -> { throw new IllegalStateException("DB down"); };
        TrackingLockProvider failed = new TrackingLockProvider(failing, failedMetrics);

        assertThatThrownBy(() -> failed.lock(CONFIG)).isInstanceOf(IllegalStateException.class);
        verify(failedMetrics).reconcileLockResult(ReconcileJob.ORPHAN_MEDIA, ReconcileLockResult.FAILED);
    }

    @Test
    void shutdownPreventsNewDatabaseLockAttempt() {
        AtomicInteger unlocks = new AtomicInteger();
        LiveMetrics metrics = mock(LiveMetrics.class);
        TrackingLockProvider provider = new TrackingLockProvider(
                ignored -> Optional.of(unlocks::incrementAndGet), metrics);

        provider.beginShutdown();

        assertThat(provider.lock(CONFIG)).isEmpty();
        assertThat(unlocks).hasValue(0);
        verify(metrics).reconcileLockResult(ReconcileJob.ORPHAN_MEDIA, ReconcileLockResult.SHUTDOWN);
    }
}
