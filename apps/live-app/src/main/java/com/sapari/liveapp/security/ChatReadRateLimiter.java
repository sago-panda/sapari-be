package com.sapari.liveapp.security;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import lombok.extern.slf4j.Slf4j;

/**
 * 공개 채팅 조회 상한 하나(이력 또는 공지) — 게스트 전역 버킷과 회원 샤드 + 회원 전역 버킷.
 *
 * <p><b>왜 가르는가(SPR-203).</b> 전역 버킷 하나일 때는 익명 호출자 하나가 한도를 다 먹으면
 * 로그인 회원도 같이 429 를 받았다. 익명에게는 신원이 없지만 회원에게는 {@code sub} 가 있으므로,
 * 입장 상한({@link EnterRateLimiter})과 같은 방식으로 나눈다. 익명 폭주는 게스트 버킷에만 걸리고,
 * 회원 한 명이 먹는 몫은 자기 샤드 하나로 묶인다.
 *
 * <p>회원 쪽 관문 순서(샤드 먼저 → 전역)와 샤드 몫 계산(올림 + 버스트 헤드룸 2)은 입장 상한과
 * 같고 근거도 같다 — {@code EnterRateLimiter.tryMemberEntry} · {@code BURST_HEADROOM} javadoc.
 * 재접속(SPR-202 {@code afterId})이 몰리는 경우가 그 헤드룸이 겨냥한 모양이다.
 *
 * <p>판매자 전용 통로는 두지 않는다. 입장은 판매자 재접속이 막히면 방송 권한을 잃지만 조회에는
 * 그런 결과가 없다.
 *
 * <p>거부 로그는 집단마다 10초에 한 줄로 솎는다. 하나로 합치면 게스트 폭주가 창을 선점해
 * 회원 거부가 안 보인다. 누적 수는 그 줄이 찍힌 시점의 수다(첫 줄은 늘 1건이고 규모는 다음 창에서 보인다).
 */
@Slf4j
public class ChatReadRateLimiter {

    private static final long REJECT_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 샤드 버스트 배수 — 값과 근거는 {@code EnterRateLimiter.BURST_HEADROOM}(실측 12.0% → 0.0%). */
    private static final int BURST_HEADROOM = 2;

    private final String label;
    private final ChatReadLimits limits;
    private final TokenBucket guestBucket;
    private final TokenBucket memberBucket;
    private final TokenBucket[] memberShards;
    private final SubjectShardKey shardKey = new SubjectShardKey();
    private final AtomicLong guestRejected = new AtomicLong();
    private final AtomicLong memberRejected = new AtomicLong();
    private final AtomicLong guestLastLogNanos;
    private final AtomicLong memberLastLogNanos;
    private final LongSupplier nanoClock;

    /** @param label 로그에 실을 이름(예: "공개 채팅 이력 조회") */
    public ChatReadRateLimiter(String label, ChatReadLimits limits) {
        this(label, limits, System::nanoTime);
    }

    /** 시계를 주입받는 생성자 — <b>테스트 전용이다</b>(이유는 {@link EnterRateLimiter} 의 같은 생성자). */
    ChatReadRateLimiter(String label, ChatReadLimits limits, LongSupplier nanoClock) {
        this.label = label;
        this.limits = limits;
        this.nanoClock = nanoClock;
        this.guestBucket = new TokenBucket(limits.guestBurst(), limits.guestPermitsPerSecond(), nanoClock);
        this.memberBucket = new TokenBucket(limits.memberBurst(), limits.memberPermitsPerSecond(), nanoClock);
        int shards = limits.memberShards();
        int shardBurst = ceilDiv(limits.memberBurst(), shards) * BURST_HEADROOM;
        int shardRefill = ceilDiv(limits.memberPermitsPerSecond(), shards);
        this.memberShards = new TokenBucket[shards];
        for (int i = 0; i < shards; i++) {
            this.memberShards[i] = new TokenBucket(shardBurst, shardRefill, nanoClock);
        }
        // 첫 거부가 반드시 남도록 간격만큼 과거로 초기화한다.
        this.guestLastLogNanos = new AtomicLong(nanoClock.getAsLong() - REJECT_LOG_INTERVAL_NANOS);
        this.memberLastLogNanos = new AtomicLong(nanoClock.getAsLong() - REJECT_LOG_INTERVAL_NANOS);
    }

    /** 미인증 조회 한 건. {@code false} 면 호출자가 429 로 돌려보낸다. */
    public boolean tryGuestRead() {
        if (guestBucket.tryAcquire()) {
            return true;
        }
        logThrottled("게스트", "전역", guestLastLogNanos, guestRejected.incrementAndGet(),
                limits.guestPermitsPerSecond(), limits.guestBurst());
        return false;
    }

    /**
     * 인증 조회 한 건 — 샤드 → 회원 전역 순서로 지난다.
     *
     * @param subject 검증된 JWT 의 주체
     */
    public boolean tryMemberRead(String subject) {
        if (!memberShards[shardIndexFor(subject)].tryAcquire()) {
            logThrottled("회원", "샤드", memberLastLogNanos, memberRejected.incrementAndGet(),
                    limits.memberPermitsPerSecond(), limits.memberBurst());
            return false;
        }
        if (memberBucket.tryAcquire()) {
            return true;
        }
        logThrottled("회원", "전역", memberLastLogNanos, memberRejected.incrementAndGet(),
                limits.memberPermitsPerSecond(), limits.memberBurst());
        return false;
    }

    /**
     * {@code Retry-After} 에 실을 초. 1 로 고정한다 — 버킷이 초당 {@code permitsPerSecond} 개를
     * 채우므로 1초 뒤에는 토큰이 있다.
     */
    public long retryAfterSeconds() {
        return 1;
    }

    /** 배치를 테스트에서 직접 보는 이음. */
    int shardIndexFor(String subject) {
        return shardKey.indexFor(subject, memberShards.length);
    }

    /** 누적 거부 수 — 테스트 진입점. */
    long guestRejectedCount() {
        return guestRejected.get();
    }

    long memberRejectedCount() {
        return memberRejected.get();
    }

    private void logThrottled(String who, String gate, AtomicLong lastLogNanos, long count,
            int permitsPerSecond, int burst) {
        if (!shouldLog(lastLogNanos)) {
            return;
        }
        log.warn("{} 상한 — {} 거부({} 관문, 누적 {}건, 한도 {}/s 버스트 {}). 프로세스 누적: 게스트={}건 회원={}건",
                label, who, gate, count, permitsPerSecond, burst, guestRejected.get(), memberRejected.get());
    }

    /** 간격을 선점한 스레드 하나만 참을 받는다(CAS). */
    private boolean shouldLog(AtomicLong gate) {
        long now = nanoClock.getAsLong();
        long last = gate.get();
        return now - last >= REJECT_LOG_INTERVAL_NANOS && gate.compareAndSet(last, now);
    }

    /** 올림 나눗셈 — 샤드 몫의 합이 전역을 넘어야 집계 천장이 전역에서 집행된다. */
    private static int ceilDiv(int dividend, int divisor) {
        return -Math.floorDiv(-dividend, divisor);
    }
}
