package com.sapari.chat.application.port;

import java.util.UUID;

import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatSession;

import reactor.core.publisher.Mono;

/**
 * 세션 관리 추상화 — 각 Pod 로컬 메모리 레지스트리 + Redis HASH를 함께 조율한다.
 * 구현체 ChatSessionRegistry는 실제 WS 채널을 쥐고 있어 transport(streaming-app)에 둔다.
 */
public interface ChatSessionManager {

    Mono<Void> register(String sessionId, ChatSession session);

    Mono<Void> unregister(UUID roomId, String sessionId);

    Mono<Void> closeUser(UUID roomId, UUID userId);

    Mono<Void> closeAll(UUID roomId);

    // 고유 유저 수(HVALS distinct) — 멀티탭은 1로 집계
    Mono<Long> getActiveCount(UUID roomId);

    // publish 실패 시 로컬 에코 폴백용 — 해당 세션에 직접 송신
    Mono<Void> sendToSession(String sessionId, OutboundMessage message);

    // 이 Pod의 해당 방 로컬 세션 전체에 송신 — SYSTEM 로컬 렌더(SystemMessageService.renderToRoom)용
    Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message);
}
