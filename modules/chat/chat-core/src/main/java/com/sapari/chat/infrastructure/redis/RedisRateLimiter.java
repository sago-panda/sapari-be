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

    /** fail-open 로그 솎아내기 간격 — 장애 중엔 전 전송이 이 경로라 건당 로그가 상황을 악화시킨다. */
    private static final long FAIL_OPEN_LOG_INTERVAL = 100;

    private final ReactiveStringRedisTemplate redisTemplate;

    /** fail-open 누적 횟수. 로그를 솎아내도 규모는 남기기 위한 카운터. */
    private final AtomicLong failOpenCount = new AtomicLong();

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
                    // 초당 수백 줄이 쌓여 정작 원인(Redis 장애) 로그를 묻는다. 그래서 솎아내고 예외 종류만 남긴다.
                    // userId도 빼는 이유: 특정 사용자 문제가 아니라 전역 상태라, 표본 하나에 찍힌 id는 오독을 부른다.
                    long count = failOpenCount.incrementAndGet();
                    if (count % FAIL_OPEN_LOG_INTERVAL == 1) {
                        log.error("rate limit Redis 장애 — fail-open으로 허용(누적 {}건) cause={}",
                                count, err.getClass().getSimpleName());
                    }
                    return Mono.just(new RateLimitResult(true, 0));
                });
    }

    private Mono<Long> remainingSeconds(String key) {
        return redisTemplate.getExpire(key)
                .map(ttl -> Math.max(1, ttl.toSeconds())) // 만료 직전 0초여도 클라 카운트다운용 최소 1
                .defaultIfEmpty(1L);
    }
}
