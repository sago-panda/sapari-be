package com.sapari.chat.application.port;

import java.util.UUID;
import java.util.function.Function;

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

    // 방 로컬 세션마다 resolver로 메시지를 만들어 송신 — 세션별 차등 fan-out(CHAT의 방주인 PII 게이팅, KICK의
    // 당사자/타인 분기). resolver가 null을 반환한 세션은 skip. (ChatBroadcastSubscriber 사용)
    // ※ 단일-메시지 sendToRoomLocal과 이름을 분리해 오버로드 모호성(any() 매처)을 피한다.
    Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver);
}
