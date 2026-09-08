package com.sapari.liveapp.config;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.ReconcileJob;
import com.sapari.live.application.port.ReconcileLockResult;

/**
 * 락 결과(획득·스킵·실패·종료중)를 지표로 남기고, 종료가 시작된 뒤에는 새 회차가 락을 잡지 못하게 한다.
 *
 * <p><b>실행 중인 회차의 락을 대신 반납하지는 않는다.</b> 한때 그런 경로를 뒀지만 도달하지 않는다 —
 * ShedLock 의 {@code DefaultLockingTaskExecutor} 가 태스크 종료 {@code finally} 에서 항상 unlock 하므로,
 * "종료 대기가 끝난 시점"에 남아 있는 락은 정의상 없다. 도달하게 만들려면 <b>아직 돌고 있는</b> 회차의
 * 락을 뺏어야 하는데, 그건 이 락이 지키려던 상호배제를 정확히 깨뜨린다(OkHttp 동기 호출은 interrupt 로
 * 취소된다는 보장이 없어 회차는 계속 돈다). 그래서 인계는 {@code lock-at-most-for} 만료가 맡는다 —
 * 없는 안전망을 있다고 적어 두는 것보다 낫다.
 */
public final class TrackingLockProvider implements LockProvider {

    private final LockProvider delegate;
    private final LiveMetrics liveMetrics;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public TrackingLockProvider(LockProvider delegate, LiveMetrics liveMetrics) {
        this.delegate = delegate;
        this.liveMetrics = liveMetrics;
    }

    @Override
    public Optional<SimpleLock> lock(LockConfiguration configuration) {
        ReconcileJob job = Arrays.stream(ReconcileJob.values())
                .filter(candidate -> candidate.lockName().equals(configuration.getName()))
                .findFirst()
                .orElse(null);
        if (shuttingDown.get()) {
            record(job, ReconcileLockResult.SHUTDOWN);
            return Optional.empty();
        }
        try {
            Optional<SimpleLock> acquired = delegate.lock(configuration);
            if (acquired.isEmpty()) {
                record(job, ReconcileLockResult.SKIPPED);
                return Optional.empty();
            }
            SimpleLock lock = acquired.orElseThrow();
            // 획득과 종료 시작이 엇갈린 창 — 여기서 잡은 락은 아직 회차를 시작하지 않았으므로
            // 되돌려도 실행 중인 작업을 건드리지 않는다.
            if (shuttingDown.get()) {
                lock.unlock();
                record(job, ReconcileLockResult.SHUTDOWN);
                return Optional.empty();
            }
            record(job, ReconcileLockResult.ACQUIRED);
            return Optional.of(lock);
        } catch (RuntimeException e) {
            record(job, ReconcileLockResult.FAILED);
            throw e;
        }
    }

    private void record(ReconcileJob job, ReconcileLockResult result) {
        if (job != null) {
            liveMetrics.reconcileLockResult(job, result);
        }
    }

    /** 종료 시작 — 이후 요청은 락을 잡지 않고 {@code shutdown} 으로 기록된다. */
    public void beginShutdown() {
        shuttingDown.set(true);
    }
}
