package com.sapari.liveapp.security;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 토큰 버킷. 초당 {@code refillPerSecond} 개씩 차고 최대 {@code capacity} 개까지 담는다.
 *
 * <p><b>시계는 {@link System#nanoTime()} 을 쓴다 — {@code TimeProvider} 가 아니다.</b> 이 저장소 규칙은
 * "서비스에서 {@code Instant.now()} 금지"인데, 여기서 필요한 건 시각이 아니라 <b>경과 시간</b>이다.
 * 벽시계(NTP 보정·서머타임)는 뒤로 점프할 수 있고, 그러면 경과가 음수가 되어 버킷이 영영 차지 않거나
 * 한 번에 가득 찬다. 레이트리밋은 단조 시계로 재야 한다.
 *
 * <p><b>토큰은 마이크로 단위 고정소수점으로 센다.</b> 나노초 단위로 "1ns 당 보충량"을 미리 계산하면
 * 초당 1,000,000 개 미만에서 정수 나눗셈이 0 으로 절삭되고, 그걸 1 로 올려 막으면 설정과 무관하게
 * 고정 속도로 차버린다(설정이 조용히 무시된다). {@code SCALE} 을 1초의 마이크로초 수와 같게 두면
 * {@code 경과 마이크로초 × 초당 허용량} 이 그대로 스케일된 토큰 수가 되어 나눗셈이 사라진다.
 */
final class TokenBucket {

    private static final long NANOS_PER_MICRO = 1_000L;
    /** 1초의 마이크로초 수이자 토큰 1개의 스케일 — 둘을 같게 두는 게 위 계산의 핵심이다. */
    private static final long SCALE = 1_000_000L;

    private final long capacityScaled;
    private final long refillPerSecond;
    /** 버킷을 가득 채우고도 남는 경과는 볼 필요가 없다 — 오래 유휴했을 때의 곱셈 오버플로를 막는다. */
    private final long maxUsefulMicros;

    private final AtomicLong tokensScaled;
    private final AtomicLong lastRefillNanos;

    TokenBucket(int capacity, int refillPerSecond) {
        this.capacityScaled = capacity * SCALE;
        this.refillPerSecond = refillPerSecond;
        this.maxUsefulMicros = capacityScaled / refillPerSecond + 1;
        this.tokensScaled = new AtomicLong(capacityScaled);
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    /** 토큰 하나를 소비한다. 남은 게 없으면 {@code false} — 호출자가 거부한다. */
    boolean tryAcquire() {
        refill();
        while (true) {
            long current = tokensScaled.get();
            if (current < SCALE) {
                return false;
            }
            if (tokensScaled.compareAndSet(current, current - SCALE)) {
                return true;
            }
        }
    }

    private void refill() {
        long last = lastRefillNanos.get();
        long elapsedNanos = System.nanoTime() - last;
        long elapsedMicros = elapsedNanos / NANOS_PER_MICRO;
        if (elapsedMicros <= 0) {
            // 마이크로초 미만이면 시계를 건드리지 않고 나간다 — 여기서 당겨두면 그 잔여가 통째로
            // 버려져, 호출이 잦을수록 버킷이 느리게 찬다.
            return;
        }
        // 시계는 경과한 만큼 <b>전부</b> 전진시킨다(나노초 잔여만 남긴다). 뒤의 상한 때문에 일부만
        // 전진시키면, 넘치도록 쉰 시간이 그대로 은행에 남아 다음 호출에 또 토큰이 되어 용량 제한이
        // 무의미해진다 — 오래 유휴한 뒤 버스트가 용량을 넘겨 터진다.
        if (!lastRefillNanos.compareAndSet(last, last + elapsedMicros * NANOS_PER_MICRO)) {
            return; // 다른 스레드가 이미 이 구간을 반영했다
        }
        // 곱셈에만 상한을 건다 — 가득 채우고 남는 경과는 어차피 버려지고, 오래 유휴했을 때의 오버플로만 막는다.
        long addedScaled = Math.min(elapsedMicros, maxUsefulMicros) * refillPerSecond;
        tokensScaled.accumulateAndGet(addedScaled, (curr, plus) -> Math.min(capacityScaled, curr + plus));
    }
}
