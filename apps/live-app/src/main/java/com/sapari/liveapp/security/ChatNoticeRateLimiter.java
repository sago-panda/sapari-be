package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 현재 공지 조회 상한 — <b>전역 버킷 하나</b>. 형제 {@link ChatHistoryRateLimiter} 와 같은
 * 모양이고, 거부 로그를 솎는 이유와 누적 수가 보이는 시점의 성질도 같다.
 */
@Slf4j
public class ChatNoticeRateLimiter {

    /** 거부 로그 간격. 형제들과 같은 값이다. */
    private static final long REJECT_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ChatNoticeRateLimitProperties properties;
    private final TokenBucket bucket;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lastLogNanos;
    private final LongSupplier nanoClock;

    public ChatNoticeRateLimiter(ChatNoticeRateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /** 시계를 주입받는 생성자 — <b>테스트 전용이다</b>(형제와 같은 이유). */
    ChatNoticeRateLimiter(ChatNoticeRateLimitProperties properties, LongSupplier nanoClock) {
        properties.normalizeAndValidate();
        this.properties = properties;
        this.nanoClock = nanoClock;
        this.bucket = new TokenBucket(properties.burst(), properties.permitsPerSecond(), nanoClock);
        // 첫 거부가 반드시 남도록 간격만큼 과거로 초기화한다(형제들과 같은 꼴).
        this.lastLogNanos = new AtomicLong(nanoClock.getAsLong() - REJECT_LOG_INTERVAL_NANOS);
    }

    /** 통과면 true. 거부면 솎은 WARN 을 남기고 false. */
    public boolean tryRead() {
        if (bucket.tryAcquire()) {
            return true;
        }
        long total = rejected.incrementAndGet();
        if (dueToLog()) {
            log.warn("공개 현재 공지 조회 상한 — 거부(누적 {}건, 한도 {}/s 버스트 {})",
                    total, properties.permitsPerSecond(), properties.burst());
        }
        return false;
    }

    /** {@code Retry-After} 에 실을 초. 1 로 고정하는 이유는 형제와 같다. */
    public long retryAfterSeconds() {
        return 1;
    }

    /** 누적 거부 수 — 테스트 진입점. */
    long rejectedCount() {
        return rejected.get();
    }

    private boolean dueToLog() {
        long now = nanoClock.getAsLong();
        long last = lastLogNanos.get();
        return now - last >= REJECT_LOG_INTERVAL_NANOS && lastLogNanos.compareAndSet(last, now);
    }
}
