package com.sapari.chat.application.port;

import reactor.core.publisher.Mono;

/**
 * 로그아웃·강제만료 Access Token 차단 — access-token:blacklist:{jti} 존재 여부 조회(읽기 전용).
 * common/auth의 블로킹 구현과 같은 Redis 키를 공유하되 streaming-app은 reactive 어댑터를 쓴다.
 *
 * <p><b>어댑터 계약</b>: Redis 장애 시 {@code false}로 흡수하지 말고 <b>error를 전파</b>한다.
 * {@code false}(=미등재)로 삼키면 소비처가 "확실히 정상 토큰"과 "조회 불가"를 구분할 수 없다.
 * <p><b>소비처(핸드셰이크) 정책은 fail-open</b>: error는 통과 허용({@code onErrorReturn(false)})으로 매핑한다.
 * 가용성 우선이며, blacklist 앞에 방 종료/진행(fail-closed) 게이트가 선행하므로 Redis 전면 장애면 이미 그
 * 단계에서 입장이 거부된다 — blacklist 단독 일시 장애일 때만 통과를 허용한다.
 */
public interface ReactiveTokenBlacklistChecker {

    Mono<Boolean> isBlacklisted(String jti);
}
