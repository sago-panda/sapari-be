package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 크로스 Pod 세션 집계 포트 — Redis HASH({@code chat:room:{roomId}:sessions}) 어댑터.
 * 실제 WS 채널 보유는 로컬 메모리 레지스트리가 맡고, 여기선 sessionId→userId 매핑만 둔다.
 */
public interface ChatSessionRepository {

    /**
     * 세션 등재. 등재와 TTL을 한 번에 처리한다.
     *
     * <p><b>어댑터 계약</b>: Redis 장애를 흡수하지 말고 <b>error를 전파</b>한다. 접속을 허용할지는 호출자
     * 정책이고, 여기서 삼키면 "등재됨"과 "등재 실패"를 구분할 수 없다.
     */
    Mono<Void> add(UUID roomId, String sessionId, UUID userId);

    Mono<Void> remove(UUID roomId, String sessionId);

    // 고유 유저 수(distinct userId) — 같은 유저 멀티탭은 1로 집계
    Mono<Long> count(UUID roomId);

    Mono<Void> clearRoom(UUID roomId);
}
