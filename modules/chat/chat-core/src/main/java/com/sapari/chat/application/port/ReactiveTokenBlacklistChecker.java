package com.sapari.chat.application.port;

import reactor.core.publisher.Mono;

/**
 * 로그아웃·강제만료 Access Token 차단 — access-token:blacklist:{jti} 존재 여부 조회(읽기 전용).
 * common/auth의 블로킹 구현과 같은 Redis 키를 공유하되 streaming-app은 reactive 어댑터를 쓴다.
 */
public interface ReactiveTokenBlacklistChecker {

    Mono<Boolean> isBlacklisted(String jti);
}
