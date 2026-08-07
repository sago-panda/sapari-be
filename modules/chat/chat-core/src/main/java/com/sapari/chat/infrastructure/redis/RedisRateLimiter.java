package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.RateLimitResult;
import com.sapari.chat.application.port.RateLimiter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * ratelimit:chat:{userId} 3초 fixed window — SET NX EX 단일 원자 연산이라
 * 동시 요청 TOCTOU race가 없다(둘 중 하나만 NX 획득). 거부는 잔여 TTL만 읽으므로 윈도우를 연장하지 않는다.
 *
 * <p>Redis 장애 시 fail-open(allowed=true) — 채팅 가용성이 레이트리밋 엄격성보다 우선(§12.1 TC#9).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final Duration WINDOW = Duration.ofSeconds(3);

    /**
     * fail-open 로그 간격. <b>건수가 아니라 경과시간</b>으로 솎아낸다 — 건수 기준(N건당 1줄)은 카운터가
     * 프로세스 생애 누적이라 두 번째 이후의 짧은 장애가 통째로 침묵한다(예: 누적 251~290이면 한 줄도 안 남음).
     * 규모가 작을수록 안 남는 역방향이라, 진단이 정작 어려운 산발 장애를 놓친다.
     */
    private static final long FAIL_OPEN_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    private final ReactiveStringRedisTemplate redisTemplate;

    /** fail-open 누적 횟수. 로그를 솎아내도 규모는 남기기 위한 카운터. */
    private final AtomicLong failOpenCount = new AtomicLong();

    /** 마지막으로 fail-open을 로깅한 시각. 첫 발생이 반드시 남도록 간격만큼 과거로 초기화한다. */
    private final AtomicLong lastFailOpenLogNanos = new AtomicLong(System.nanoTime() - FAIL_OPEN_LOG_INTERVAL_NANOS);

    @Override
    public Mono<RateLimitResult> tryAcquire(UUID userId) {
        String key = ChatRedisKeys.rateLimit(userId);
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", WINDOW)
                .flatMap(acquired -> acquired
                        ? Mono.just(new RateLimitResult(true, 0))
                        : remainingSeconds(key).map(remaining -> new RateLimitResult(false, remaining)))
                .onErrorResume(err -> {
                    // Redis가 죽으면 전 사용자의 모든 전송이 이 경로를 탄다. 건당 스택트레이스를 남기면
                    // 초당 수백 줄이 쌓여 정작 원인(Redis 장애) 로그를 묻는다. 그래서 간격으로 솎아낸다.
                    // userId는 뺀다: SET NX는 키별 오류 경로가 없어 여기 오는 건 사실상 전역 장애이고,
                    // 표본 하나에 찍힌 id는 "이 사용자 문제"로 오독을 부른다. 대신 예외를 그대로 실어
                    // 위치를 남긴다 — 이 catch는 전 예외를 받으므로 체인 내부 결함이 흘러들 수도 있는데,
                    // 그때는 스택트레이스가 id보다 훨씬 쓸모 있다.
                    long count = failOpenCount.incrementAndGet();
                    if (shouldLogFailOpen()) {
                        log.error("rate limit Redis 장애 — fail-open으로 허용(누적 {}건)", count, err);
                    }
                    return Mono.just(new RateLimitResult(true, 0));
                });
    }

    /** 마지막 로그로부터 간격이 지났으면 true. 경쟁 시 한 스레드만 통과한다(스로틀이 곧 중복 억제). */
    private boolean shouldLogFailOpen() {
        long now = System.nanoTime();
        long last = lastFailOpenLogNanos.get();
        return now - last >= FAIL_OPEN_LOG_INTERVAL_NANOS && lastFailOpenLogNanos.compareAndSet(last, now);
    }

    private Mono<Long> remainingSeconds(String key) {
        return redisTemplate.getExpire(key)
                .map(ttl -> Math.max(1, ttl.toSeconds())) // 만료 직전 0초여도 클라 카운트다운용 최소 1
                .defaultIfEmpty(1L);
    }
}
