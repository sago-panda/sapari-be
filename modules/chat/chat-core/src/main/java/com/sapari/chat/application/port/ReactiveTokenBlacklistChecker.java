package com.sapari.chat.application.port;

import reactor.core.publisher.Mono;

/**
 * 로그아웃·강제만료 Access Token 차단 — access-token:blacklist:{jti} 존재 여부 조회(읽기 전용).
 * common/auth의 블로킹 구현과 같은 Redis 키를 공유하되 streaming-app은 reactive 어댑터를 쓴다.
 *
 * <p><b>⚠️ fail-closed 계약</b>: Redis 장애 시 구현은 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다.
 * 소비처(핸드셰이크)는 그 error를 <b>연결 거부</b>로 매핑해야 한다 — error를 {@code false}로 처리하면
 * 로그아웃/폐기된 토큰이 Redis 순단 중 접속을 통과한다(보안 게이트 우회). RateLimiter의 fail-open과 정반대다.
 */
public interface ReactiveTokenBlacklistChecker {

    Mono<Boolean> isBlacklisted(String jti);
}
