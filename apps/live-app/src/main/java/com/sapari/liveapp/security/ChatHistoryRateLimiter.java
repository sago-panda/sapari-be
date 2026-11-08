package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 채팅 이력 조회 상한 — <b>전역 버킷 하나</b>.
 *
 * <p>왜 주체별로 안 가르는지, 그 대가가 무엇인지는 {@link ChatHistoryRateLimitProperties} javadoc 에
 * 있다. 요약하면 이 엔드포인트는 {@code permitAll} 이라 익명 호출자에게 샤드 키로 쓸 신원이 없다.
 *
 * <p>거부 로그는 솎는다. 형제들이 같은 이유를 적는다 — 거부마다 한 줄을 남기면 로그 I/O 가
 * <b>폭주 규모에 비례</b>하고, 그 사이 다른 WARN·ERROR 이 묻힌다.
 *
 * <p>⚠️ <b>누적 수는 "그 줄이 찍힌 시점" 의 수다.</b> 첫 거부는 즉시 찍히므로 그 줄은 늘
 * {@code 누적 1건} 이고, 억제된 구간의 규모는 <b>다음 창의 줄</b>에서 보인다. 그래서 "누적 수를
 * 실어 규모를 잃지 않는다" 는 <b>두 번째 창부터</b> 참이다 — 한 창 안에서 끝나는 짧은 폭주는
 * 로그에 "1건" 으로만 남는다. 규모를 창 단위로 보려면 {@link #rejectedCount()} 쪽이고, 형제들
 * ({@code EntryGate} · {@code ChatSessionRegistry})도 같은 성질이다.
 */
@Slf4j
public class ChatHistoryRateLimiter {

    /** 거부 로그 간격. 형제 {@code EnterRateLimiter} 와 같은 값이다. */
    private static final long REJECT_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ChatHistoryRateLimitProperties properties;
    private final TokenBucket bucket;
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lastLogNanos;
    private final LongSupplier nanoClock;

    public ChatHistoryRateLimiter(ChatHistoryRateLimitProperties properties) {
        this(properties, System::nanoTime);
    }

    /**
     * 시계를 주입받는 생성자 — <b>테스트 전용이다.</b> 형제 {@code EnterRateLimiter} 와 같은 이음이고
     * 이유도 같다: 가장 느린 버킷도 초당 1개는 차므로 "비었다" 를 단정하는 창이 1초뿐이고, CI 부하로
     * 그 창을 넘기면 리필이 토큰을 돌려줘 단정이 깨진다. 같은 시계가 로그 억제에도 쓰여 그쪽도
     * 결정론이 된다.
     */
    ChatHistoryRateLimiter(ChatHistoryRateLimitProperties properties, LongSupplier nanoClock) {
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
            log.warn("공개 채팅 이력 조회 상한 — 거부(누적 {}건, 한도 {}/s 버스트 {})",
                    total, properties.permitsPerSecond(), properties.burst());
        }
        return false;
    }

    /**
     * {@code Retry-After} 에 실을 초.
     *
     * <p>1 로 고정한다 — 버킷이 초당 {@code permitsPerSecond} 개를 채우므로 1초 뒤에는 반드시
     * 토큰이 있다. 더 긴 값을 주면 복구를 늦추고, 0 을 주면 클라가 즉시 재시도해 거부 비용만 돈다.
     */
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
