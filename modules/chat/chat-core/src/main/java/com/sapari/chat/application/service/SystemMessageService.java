package com.sapari.chat.application.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

/**
 * SYSTEM 메시지(강퇴·종료·밴 등 전이성 신호) 로컬 렌더 전용 — MongoDB 미영속·Pub/Sub 미발행.
 *
 * <p>SYSTEM은 각 Pod가 Pub/Sub 이벤트(KICK_EVENT·ROOM_ENDED) 수신 후 자기 로컬 세션에 직접 렌더한다.
 * 그래서 저장/브로드캐스트가 아니라 {@link ChatSessionManager}로 로컬 세션에만 송신한다.
 * 와이어엔 텍스트(displayMessage)를 싣지 않고 {@code code}만 보내며, 표시 문구는 클라이언트가 code로 렌더한다.
 */
@Service
@RequiredArgsConstructor
public class SystemMessageService {

    private final ChatSessionManager sessionManager;

    /** 특정 세션 1개에 SYSTEM 렌더 (예: 강퇴 대상 본인에게 KICKED). */
    public Mono<Void> renderToSession(String sessionId, SystemMessageCode code) {
        return sessionManager.sendToSession(sessionId, OutboundMessage.system(code));
    }

    /** 이 Pod의 방 로컬 세션 전체에 SYSTEM 렌더 (예: ROOM_ENDED). */
    public Mono<Void> renderToRoom(UUID roomId, SystemMessageCode code) {
        return sessionManager.sendToRoomLocal(roomId, OutboundMessage.system(code));
    }

}
