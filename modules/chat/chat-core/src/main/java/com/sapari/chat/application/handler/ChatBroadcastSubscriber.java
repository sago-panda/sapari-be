package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * chat:pubsub 구독자 — 다른 Pod(또는 같은 Pod)가 발행한 봉투를 받아 이 Pod의 로컬 세션에 렌더한다.
 *
 * <ul>
 *   <li><b>CHAT</b>: 방 로컬 세션에 fan-out. <b>방 주인(isRoomOwner) 세션만</b> senderEmail·원문(originalMessage)
 *       포함(toOwnerView), 그 외는 마스킹된 displayMessage만(toView). PII 게이팅은 와이어가 아니라 이 fan-out
 *       시점에 적용된다(봉투엔 평문 PII 잔존 — 크로스 Pod 방주인 렌더에 필요, §8.2).
 *   <li><b>KICK_EVENT</b>: 강퇴 당사자 세션엔 SYSTEM(KICKED) 후 WS close, 그 외 세션엔 KICK(userId).
 * </ul>
 *
 * <p>구독 시작/해제(어느 방을 언제 subscribe 할지)는 WS 핸들러가 방 첫 입장/마지막 퇴장에 맞춰 호출한다
 * (broadcaster는 패턴 구독으로 상시 가동이라 여기 subscribe는 hot 스트림에 필터를 붙이는 것뿐 — 레이스 없음).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatBroadcastSubscriber {

    private static final String SYSTEM_NICKNAME = "SYSTEM";

    private final ChatBroadcaster broadcaster;
    private final ChatSessionManager sessionManager;

    /** 방 구독 시작 — 반환 Disposable로 마지막 퇴장 시 해제. 봉투 1건의 라우팅 실패가 스트림을 죽이지 않게 흡수. */
    public Disposable subscribeRoom(UUID roomId) {
        return broadcaster.subscribe(roomId)
                .flatMap(envelope -> route(roomId, envelope)
                        .onErrorResume(e -> {
                            log.error("chat:pubsub 라우팅 실패 — 해당 봉투 skip(구독 유지) roomId={}", roomId, e);
                            return Mono.empty();
                        }))
                .subscribe();
    }

    /** 봉투 1건을 로컬 세션에 라우팅. (테스트 진입점) */
    Mono<Void> route(UUID roomId, ChatEnvelope envelope) {
        return switch (envelope) {
            case ChatEnvelope.ChatMsg chat -> fanOutChat(roomId, chat.message());
            case ChatEnvelope.KickEvent kick -> fanOutKick(roomId, kick.userId());
        };
    }

    private Mono<Void> fanOutChat(UUID roomId, ChatMessage message) {
        OutboundMessage ownerView = toOutbound(message, true);
        OutboundMessage normalView = toOutbound(message, false);
        return sessionManager.sendToRoomGated(roomId,
                session -> session.isRoomOwner() ? ownerView : normalView);
    }

    private Mono<Void> fanOutKick(UUID roomId, UUID kickedUserId) {
        OutboundMessage kicked = kickedSystem();
        OutboundMessage kickNotice = kickNotice(kickedUserId);
        // 당사자엔 KICKED, 그 외엔 KICK 전송 → 그 다음 당사자 세션 close(KICKED가 close보다 먼저 도착하도록 순서 보장)
        return sessionManager.sendToRoomGated(roomId,
                        session -> session.userId().equals(kickedUserId) ? kicked : kickNotice)
                .then(sessionManager.closeUser(roomId, kickedUserId));
    }

    /** ChatMessage → 렌더용 OutboundMessage. ownerView일 때만 senderEmail·원문 포함(secure-by-default). */
    private OutboundMessage toOutbound(ChatMessage m, boolean ownerView) {
        return new OutboundMessage(
                typeName(m.type()),                       // NORMAL | NOTICE
                null,                                     // code
                m.id(),                                   // MongoDB ObjectId
                m.senderId(),
                m.senderNickname(),
                m.senderRole(),
                ownerView ? m.senderEmail() : null,       // PII — 방주인만
                m.displayMessage(),
                ownerView ? m.originalMessage() : null,   // 원문 — 방주인만(강퇴 판단)
                m.createdAt(),
                null,                                     // userId — KICK 전용
                null,                                     // activeCount — ROOM_INFO 전용
                null,                                     // retryAfterSeconds — RATE_LIMIT 전용
                null,                                     // clientMsgId — 브로드캐스트라 없음(send 결과만 운반)
                null                                      // isRoomOwner — ROOM_INFO 전용
        );
    }

    private String typeName(ChatMessageType type) {
        return switch (type) {
            case ChatMessageType.Normal n -> "NORMAL";
            case ChatMessageType.Notice no -> "NOTICE";
            case ChatMessageType.System s -> "SYSTEM";   // 와이어엔 안 옴 — sealed 전수성 방어
        };
    }

    /** 강퇴 당사자에게 보내는 SYSTEM(KICKED). */
    private OutboundMessage kickedSystem() {
        return new OutboundMessage(
                "SYSTEM", SystemMessageCode.KICKED.name(), null,
                ChatConstants.SYSTEM_SENDER_ID, SYSTEM_NICKNAME, null,
                null, null, null, null, null, null, null, null, null);
    }

    /** 그 외 세션에게 보내는 KICK(userId) — 프론트가 해당 userId 메시지 숨김. */
    private OutboundMessage kickNotice(UUID kickedUserId) {
        return new OutboundMessage(
                "KICK", null, null, null, null, null,
                null, null, null, null, kickedUserId, null, null, null, null);
    }
}
