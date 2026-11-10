package com.sapari.live.infrastructure.recentview;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.springframework.core.NestedExceptionUtils;

import com.sapari.live.application.port.LiveMetrics;
import com.sapari.live.application.port.RecentViewDropReason;
import com.sapari.live.application.port.RecentViewRecorder;
import com.sapari.live.domain.model.RecentView;

/**
 * 최근 본 기록을 전용 실행기에 넘긴다. 입장 요청 스레드는 제출만 하고 돌아간다 — DB 커넥션을 잡지 않으므로
 * 입장의 "서명이 커넥션을 쥐지 않는다"(SPR-166) 전제가 그대로이고, DB 가 느려도 입장 응답이 기다리지 않는다.
 * 기록이 쓰는 커넥션은 실행기 스레드 수({@code RecentViewConfig})로 묶인다.
 *
 * <p><b>⚠️ 쓰기 예외를 다시 던지지 않는다 — 헌법 IV 의 의도된 예외다.</b> 응답이 이미 나간 비동기 작업이라 받을
 * 호출자가 없고, 던지면 실행기의 기본 처리기가 입장 수만큼 스택트레이스를 남긴다. 대신 규모는
 * {@code live.recent_view.dropped{reason}} 로, 원인은 10초에 한 줄 WARN 으로 드러낸다
 * (선례: {@code LiveListCacheSync} 의 "삼키고 센다", {@code EnterRateLimiter} 의 솎은 로그).
 * 잃는 것은 그 기록 하나이고, 같은 방에 다시 들어오면 다시 남는다.
 */
@Slf4j
public class AsyncRecentViewRecorder implements RecentViewRecorder {

    private static final long LOG_INTERVAL_NANOS = 10_000_000_000L; // 10s

    private final Executor executor;
    private final Consumer<RecentView> writer;
    private final LiveMetrics metrics;
    private final LongSupplier nanoClock;
    private final AtomicLong rejectedLastLogNanos;
    private final AtomicLong failedLastLogNanos;

    /** @param writer 한 건 쓰기(트랜잭션 포함) — 운영에서는 {@code RecentViewWriter::write} */
    public AsyncRecentViewRecorder(Executor executor, Consumer<RecentView> writer, LiveMetrics metrics,
                                   LongSupplier nanoClock) {
        this.executor = executor;
        this.writer = writer;
        this.metrics = metrics;
        this.nanoClock = nanoClock;
        long start = nanoClock.getAsLong() - LOG_INTERVAL_NANOS; // 첫 실패는 바로 찍는다
        this.rejectedLastLogNanos = new AtomicLong(start);
        this.failedLastLogNanos = new AtomicLong(start);
    }

    @Override
    public void record(UUID userId, UUID roomId, Instant viewedAt) {
        RecentView view = new RecentView(userId, roomId, viewedAt);
        try {
            executor.execute(() -> write(view));
        } catch (RejectedExecutionException e) { // TaskRejectedException 포함
            metrics.recentViewDropped(RecentViewDropReason.REJECTED);
            if (shouldLog(rejectedLastLogNanos)) {
                log.warn("최근 본 기록 대기열이 가득해 버렸다 — 입장이 쓰기 처리량을 넘었다. "
                        + "규모는 live.recent_view.dropped{reason=rejected} 로 본다.");
            }
        }
    }

    private void write(RecentView view) {
        try {
            writer.accept(view);
        } catch (RuntimeException e) {
            metrics.recentViewDropped(RecentViewDropReason.FAILED);
            if (shouldLog(failedLastLogNanos)) {
                // 예외 클래스만 싣는다 — 메시지는 드라이버에 따라 바인딩 값(회원·방 식별자)을 담을 수 있고,
                // 장애 동안 시청 이력이 로그로 샌다. 타임아웃·연결 실패·권한은 클래스로 갈린다.
                log.warn("최근 본 기록 쓰기 실패로 버렸다(입장은 영향 없음). 원인={} (근원 {}). "
                                + "규모는 live.recent_view.dropped{reason=failed} 로 본다.",
                        e.getClass().getSimpleName(),
                        NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName());
            }
        }
    }

    private boolean shouldLog(AtomicLong gate) {
        long now = nanoClock.getAsLong();
        long last = gate.get();
        return now - last >= LOG_INTERVAL_NANOS && gate.compareAndSet(last, now);
    }
}
