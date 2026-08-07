package com.sapari.chat.application.port;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 채팅 전송 레이트리밋. 면제는 <b>이 방송을 진행하는 판매자</b>(상품 설명 연속 전송)와 운영자뿐이고,
 * 남의 방에 시청자로 들어온 판매자는 구매자와 동일하게 적용된다 — 면제 근거가 진행자에게만 해당한다.
 * userId 기준 글로벌 — 한 유저가 여러 방을 동시 시청해도 공유한다.
 */
public interface RateLimiter {

    Mono<RateLimitResult> tryAcquire(UUID userId);
}
