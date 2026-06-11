package com.sapari.chat.application.port;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 채팅 전송 레이트리밋. BUYER에게만 적용(SELLER·ADMIN은 상품 설명 등 연속 전송 허용).
 * userId 기준 글로벌 — 한 유저가 여러 방을 동시 시청해도 공유한다.
 */
public interface RateLimiter {

    Mono<RateLimitResult> tryAcquire(UUID userId);
}
