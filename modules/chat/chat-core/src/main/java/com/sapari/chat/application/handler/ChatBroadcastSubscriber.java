package com.sapari.chat.application.handler;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.domain.model.ChatMessage;

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
 *       시점에 적용된다(봉투엔 평문 PII 잔존 — 방주인이 다른 Pod에 붙어 있을 수 있어 필요하다).
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

    /**
     * 방 전체에 채팅 1건을 뿌린다. 세션 축이 둘이라 조합을 미리 만들어 두고 세션마다 고르기만 한다
     * (세션 수에 비례해 객체를 새로 만들지 않는다).
     *
     * <p><b>발신자 축</b>: 보낸 사람에게는 {@code clientMsgId}를 함께 실어 보낸다. 발신자는 같은 메시지를
     * 낙관적 렌더·ACK·브로드캐스트로 세 번 마주치는데, 브로드캐스트에 이 값이 없으면 ACK가 도착해 서버 id를
     * 알기 전까지는 자기 버블과 짝지을 수단이 없다. ACK가 먼저 온다는 보장이 없어(ACK는 직접 응답, 브로드캐스트는
     * Redis 왕복) 순서가 뒤집히면 중복으로 그려진다. 남에게는 싣지 않는다 — 남의 클라 생성 id다.
     *
     * <p><b>판정 축은 세션이 아니라 유저다</b>: resolver가 받는 건 도메인 세션이라 어느 탭이 보냈는지 알 수 없다.
     * 그래서 같은 계정의 <b>다른 탭</b>도 이 값을 받는다 — 그 탭엔 짝지을 버블이 없다. 받는 쪽 규칙은
     * "값이 있으면 내 것"이 아니라 <b>"내 대기 목록에 있을 때만 매칭"</b>이어야 한다. 세션 단위로 좁히려면
     * 세션 식별자를 영속 메시지까지 관통시켜야 해서 얻는 것보다 잃는 게 크다.
     */
    private Mono<Void> fanOutChat(UUID roomId, ChatMessage message) {
        OutboundMessage ownerView = OutboundMessage.chat(message, true, false);
        OutboundMessage normalView = OutboundMessage.chat(message, false, false);
        OutboundMessage ownerSenderView = OutboundMessage.chat(message, true, true);
        OutboundMessage senderView = OutboundMessage.chat(message, false, true);
        return sessionManager.sendToRoomGated(roomId, session -> {
            boolean sender = session.userId().equals(message.senderId());
            return session.isRoomOwner()
                    ? (sender ? ownerSenderView : ownerView)
                    : (sender ? senderView : normalView);
        });
    }

    private Mono<Void> fanOutKick(UUID roomId, UUID kickedUserId) {
        OutboundMessage kicked = OutboundMessage.system(SystemMessageCode.KICKED);
        OutboundMessage kickNotice = OutboundMessage.kick(kickedUserId);
        // 당사자엔 KICKED, 그 외엔 KICK 전송 → 그 다음 당사자 세션 close(KICKED가 close보다 먼저 도착하도록 순서 보장)
        return sessionManager.sendToRoomGated(roomId,
                        session -> session.userId().equals(kickedUserId) ? kicked : kickNotice)
                .then(sessionManager.closeUser(roomId, kickedUserId));
    }

}
