package com.sapari.liveapp.security;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

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
    private final LongSupplier nanoClock;

    TokenBucket(int capacity, int refillPerSecond) {
        this(capacity, refillPerSecond, System::nanoTime);
    }

    /**
     * 시계를 주입받는 생성자 — <b>테스트 전용이다.</b>
     *
     * <p>가장 느린 버킷도 초당 1개는 차므로({@code permitsPerSecond} 가 양의 int) 테스트가
     * "비었다" 를 단정하는 창이 <b>1초</b>다. GC·CI 부하로 그 창을 넘기면 리필이 토큰을
     * 돌려줘 단정이 조용히 통과한다 — 방향은 오탐이지만 그 테스트는 아무것도 재지 않게 된다.
     * 설정으로는 닫을 수 없어 이음을 하나 낸다.
     */
    TokenBucket(int capacity, int refillPerSecond, LongSupplier nanoClock) {
        this.capacityScaled = capacity * SCALE;
        this.refillPerSecond = refillPerSecond;
        this.maxUsefulMicros = capacityScaled / refillPerSecond + 1;
        this.tokensScaled = new AtomicLong(capacityScaled);
        this.nanoClock = nanoClock;
        this.lastRefillNanos = new AtomicLong(nanoClock.getAsLong());
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
        long elapsedNanos = nanoClock.getAsLong() - last;
        long elapsedMicros = elapsedNanos / NANOS_PER_MICRO;
        if (elapsedMicros <= 0) {
            // 마이크로초 미만이면 시계를 건드리지 않고 나간다 — 여기서 당겨두면 그 잔여가 통째로
            // 버려져, 호출이 잦을수록 버킷이 느리게 찬다.
            return;
        }
        // ⚠️ 이 CAS 를 set 으로 바꾸는 회귀는 <어느 테스트도 안 잡는다>(분모 25에 벡터 0).
        // 두 스레드가 같은 last·같은 elapsedMicros 를 읽고 둘 다 더하면 한 구간에 N배가
        // 들어가고, 클램프는 capacity 에만 걸려 버킷이 용량 아래에 머무는 동안 지속 허용량이
        // 설정값을 넘는다. 동시 소비 창(TokenBucketTest)으로는 안 닫힌다 — 그쪽은 용량 초과로
        // 죽고 이것은 용량 아래에 머문다. 같은 구간에 두 스레드를 세우는 칸이 따로 필요하다.
        //
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
