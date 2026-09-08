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

    /**
     * 이 사람의 세션을 <b>방을 가리지 않고</b> 이 Pod에서 전부 끊는다.
     *
     * <p>{@link #closeUser(UUID, UUID)}와 갈라 두는 이유는 조치의 범위가 다르기 때문이다. 강퇴는 그 방
     * 하나이지만 밴·탈퇴는 계정 전체라, 그 사람이 다른 방에 열어 둔 세션도 끊어야 한다.
     *
     * <p>방 색인을 쓸 수 없어 이 Pod의 세션을 훑는다. 그래도 되는 이유는 이 조치가 드물기 때문이다 —
     * 방 fan-out은 메시지마다 돌아 색인이 필요했지만, 밴은 시간당 몇 건이다.
     */
    Mono<Void> closeUserEverywhere(UUID userId);

    // 고유 유저 수(HVALS distinct) — 멀티탭은 1로 집계
    Mono<Long> getActiveCount(UUID roomId);

    // publish 실패 시 로컬 에코 폴백용 — 해당 세션에 직접 송신
    Mono<Void> sendToSession(String sessionId, OutboundMessage message);

    // 이 Pod의 해당 방 로컬 세션 전체에 송신 — SYSTEM 로컬 렌더(SystemMessageService.renderToRoom)용
    Mono<Void> sendToRoomLocal(UUID roomId, OutboundMessage message);

    /**
     * 이 사람의 세션에 <b>방을 가리지 않고</b> 같은 메시지를 보낸다.
     *
     * <p>끊기 직전에 사유를 알리는 데 쓴다. 순서가 계약이다 — 먼저 끊으면 sink가 닫혀 뒤이은 사유 프레임이
     * 조용히 사라지고, 클라이언트는 닫힌 이유를 모른 채 재접속을 시도한다.
     */
    Mono<Void> sendToUserEverywhere(UUID userId, OutboundMessage message);

    // 방 로컬 세션마다 resolver로 메시지를 만들어 송신 — 세션별 차등 fan-out(CHAT의 방주인 PII 게이팅, KICK의
    // 당사자/타인 분기). resolver가 null을 반환한 세션은 skip. (ChatBroadcastSubscriber 사용)
    // ※ 단일-메시지 sendToRoomLocal과 이름을 분리해 오버로드 모호성(any() 매처)을 피한다.
    Mono<Void> sendToRoomGated(UUID roomId, Function<ChatSession, OutboundMessage> resolver);
}
