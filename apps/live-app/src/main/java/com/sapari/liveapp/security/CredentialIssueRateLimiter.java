package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 판매자 자격증명 발급 상한 하나(썸네일 업로드 허가 또는 송출 키 재발급) — 판매자 샤드 + 판매자 전역.
 *
 * <p><b>왜 필요한가(SPR-207).</b> 두 경로 모두 요청 하나가 외부 자원을 <b>증폭</b>해서 쓴다.
 * 썸네일은 S3 업로드 허가를 찍어 주고(허가 하나가 그 뒤 업로드 용량이다), 재발급은 LiveKit 을
 * 최대 5번 부른다({@code createIngress} 1 + {@code deleteIngress}·{@code listRoomIngress} 각 2).
 * {@code Scheduled} 인 동안 횟수 제한이 없어서, 방 하나로 LiveKit 호출을 무한히 낼 수 있었다.
 *
 * <p><b>게스트 버킷을 두지 않는다</b> — 이유는 {@link CredentialIssueLimits} javadoc. 신원이 없는
 * 요청은 {@link CredentialIssueRateLimitFilter} 가 그대로 넘기고 인가 필터가 401 로 끝낸다.
 *
 * <p>관문 순서(샤드 먼저 → 전역)와 샤드 몫 계산(올림 + 버스트 헤드룸 2)은 입장 상한과 같고 근거도
 * 같다 — {@code EnterRateLimiter.tryMemberEntry} · {@code BURST_HEADROOM} javadoc.
 *
 * <p>거부 로그는 10초에 한 줄로 솎는다. 집단이 하나라 창도 하나다(형제 {@link ChatReadRateLimiter}
 * 는 게스트 폭주가 회원 거부를 가리지 않게 둘로 나눴지만, 여기는 가릴 상대가 없다).
 *
 * <p>⚠️ <b>상한은 재발급의 배타성을 대신하지 않는다.</b> 경합 배타성은 CAS 한 문장
 * ({@code RtmpIngressAssigner.reassignIfCurrent})에 있고, 이 버킷은 호출량 천장일 뿐이다 — 상한을
 * 올려도 CAS 가 정합성을 지키고, 상한에 걸려도 CAS 가 느슨해지지 않는다.
 */
@Slf4j
public class CredentialIssueRateLimiter {

    private static final long REJECT_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 샤드 버스트 배수 — 값과 근거는 {@code EnterRateLimiter.BURST_HEADROOM}(실측 12.0% → 0.0%). */
    private static final int BURST_HEADROOM = 2;

    private final String label;
    private final CredentialIssueLimits limits;
    private final TokenBucket sellerBucket;
    private final TokenBucket[] sellerShards;
    private final SubjectShardKey shardKey = new SubjectShardKey();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lastLogNanos;
    private final LongSupplier nanoClock;

    /** @param label 로그에 실을 이름(예: "썸네일 업로드 허가") */
    public CredentialIssueRateLimiter(String label, CredentialIssueLimits limits) {
        this(label, limits, System::nanoTime);
    }

    /** 시계를 주입받는 생성자 — <b>테스트 전용이다</b>(이유는 {@link TokenBucket} 의 같은 생성자). */
    CredentialIssueRateLimiter(String label, CredentialIssueLimits limits, LongSupplier nanoClock) {
        this.label = label;
        this.limits = limits;
        this.nanoClock = nanoClock;
        this.sellerBucket = new TokenBucket(limits.sellerBurst(), limits.sellerPermitsPerSecond(), nanoClock);
        int shards = limits.sellerShards();
        int shardBurst = ceilDiv(limits.sellerBurst(), shards) * BURST_HEADROOM;
        int shardRefill = ceilDiv(limits.sellerPermitsPerSecond(), shards);
        this.sellerShards = new TokenBucket[shards];
        for (int i = 0; i < shards; i++) {
            this.sellerShards[i] = new TokenBucket(shardBurst, shardRefill, nanoClock);
        }
        // 첫 거부가 반드시 남도록 간격만큼 과거로 초기화한다.
        this.lastLogNanos = new AtomicLong(nanoClock.getAsLong() - REJECT_LOG_INTERVAL_NANOS);
    }

    /**
     * 발급 한 건 — 샤드 → 전역 순서로 지난다. {@code false} 면 호출자가 429 로 돌려보낸다.
     *
     * @param subject 검증된 JWT 의 주체(판매자 id)
     */
    public boolean tryIssue(String subject) {
        if (!sellerShards[shardIndexFor(subject)].tryAcquire()) {
            logThrottled("샤드");
            return false;
        }
        if (sellerBucket.tryAcquire()) {
            return true;
        }
        logThrottled("전역");
        return false;
    }

    /**
     * {@code Retry-After} 에 실을 초. 1 로 고정한다 — 버킷이 초당
     * {@code sellerPermitsPerSecond} 개를 채우므로 1초 뒤에는 전역에 토큰이 있다.
     *
     * <p>⚠️ 샤드 관문에서 거부된 경우는 샤드 리필(⌈전역/샤드수⌉)이 기준이라 1초가 낙관적일 수
     * 있다. 그래도 나누지 않는다 — 어느 관문에서 막혔는지 응답으로 알리면 샤드 배치를 탐색하는
     * 단서가 되고, 샤드 키는 그걸 막으려고 프로세스 로컬 랜덤이다({@link SubjectShardKey}).
     */
    public long retryAfterSeconds() {
        return 1;
    }

    /** 배치를 테스트에서 직접 보는 이음. */
    int shardIndexFor(String subject) {
        return shardKey.indexFor(subject, sellerShards.length);
    }

    /** 누적 거부 수 — 테스트 진입점. */
    long rejectedCount() {
        return rejected.get();
    }

    private void logThrottled(String gate) {
        long count = rejected.incrementAndGet();
        if (!shouldLog()) {
            return;
        }
        log.warn("{} 상한 — 판매자 거부({} 관문, 누적 {}건, 한도 {}/s 버스트 {} 샤드 {}개)",
                label, gate, count, limits.sellerPermitsPerSecond(), limits.sellerBurst(),
                limits.sellerShards());
    }

    /** 간격을 선점한 스레드 하나만 참을 받는다(CAS). */
    private boolean shouldLog() {
        long now = nanoClock.getAsLong();
        long last = lastLogNanos.get();
        return now - last >= REJECT_LOG_INTERVAL_NANOS && lastLogNanos.compareAndSet(last, now);
    }

    /** 올림 나눗셈 — 샤드 몫의 합이 전역을 넘어야 집계 천장이 전역에서 집행된다. */
    private static int ceilDiv(int dividend, int divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }
}
