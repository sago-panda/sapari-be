package com.sapari.chat.domain.repository;

import java.util.UUID;

import reactor.core.publisher.Mono;

/**
 * 크로스 Pod 세션 집계 포트 — Redis HASH(room:{roomId}:sessions) 어댑터.
 * 실제 WS 채널 보유는 로컬 메모리 레지스트리가 맡고, 여기선 sessionId→userId 매핑만 둔다.
 */
public interface ChatSessionRepository {

    Mono<Void> add(UUID roomId, String sessionId, UUID userId);

    Mono<Void> remove(UUID roomId, String sessionId);

    // 고유 유저 수(distinct userId) — 같은 유저 멀티탭은 1로 집계
    Mono<Long> count(UUID roomId);

    Mono<Void> clearRoom(UUID roomId);
}
