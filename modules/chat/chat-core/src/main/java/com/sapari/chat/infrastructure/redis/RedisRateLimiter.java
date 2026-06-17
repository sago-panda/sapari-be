package com.sapari.chat.infrastructure.redis;

import java.time.Duration;
import java.util.UUID;

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

    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<RateLimitResult> tryAcquire(UUID userId) {
        String key = ChatRedisKeys.rateLimit(userId);
        return redisTemplate.opsForValue()
                .setIfAbsent(key, "1", WINDOW)
                .flatMap(acquired -> acquired
                        ? Mono.just(new RateLimitResult(true, 0))
                        : remainingSeconds(key).map(remaining -> new RateLimitResult(false, remaining)))
                .onErrorResume(err -> {
                    log.error("rate limit Redis 장애 — fail-open으로 허용 userId={}", userId, err);
                    return Mono.just(new RateLimitResult(true, 0));
                });
    }

    private Mono<Long> remainingSeconds(String key) {
        return redisTemplate.getExpire(key)
                .map(ttl -> Math.max(1, ttl.toSeconds())) // 만료 직전 0초여도 클라 카운트다운용 최소 1
                .defaultIfEmpty(1L);
    }
}
