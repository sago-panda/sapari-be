package com.sapari.streamingapp.websocket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.exception.UserKickedException;
import com.sapari.chat.domain.model.ChatConstants;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.streamingapp.websocket.auth.RoomTokenVerifier;
import com.sapari.streamingapp.websocket.auth.WebSocketAuthException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 채팅 WS 핸들러 — 입장 게이트(룸 토큰·강퇴)부터 송수신 배선까지 한 연결의 생애를 묶는다.
 *
 * <p>흐름: 쿼리(roomId·token) 파싱 → 룸 토큰 검증({@link RoomTokenVerifier}) → 입장 게이트({@link EntryGate})
 * → register + 방 구독 acquire → ROOM_INFO 송신 → 아웃바운드(Sink→session.send)·인바운드(receive→send())
 * 동시 가동 → 종료 시 unregister + 방 구독 release.
 *
 * <p><b>구독 ref-count</b>: 같은 방의 여러 세션이 한 패턴 구독을 공유한다 — 방 첫 입장 시 subscribeRoom,
 * 마지막 퇴장 시 dispose. (broadcaster가 패턴 구독으로 상시 가동이라 acquire는 hot 스트림에 필터 붙이기일 뿐, 레이스 없음.)
 *
 * <p>@Component를 붙이지 않는다 — SendChatUseCase(application.service) 빈 와이어가 갖춰지는 풀와이어 단계(C5)에서
 * WebSocketConfig가 생성·등록한다. (그 전 부팅을 깨지 않으려 스캔 대상에서 제외)
 */
@Slf4j
public class ChatWebSocketHandler implements WebSocketHandler {

    private static final String SYSTEM_NICKNAME = "SYSTEM";

    private final RoomTokenVerifier verifier;
    private final EntryGate entryGate;
    private final ChatSessionRegistry registry;
    private final SendChatUseCase sendUseCase;
    private final ChatBroadcastSubscriber subscriber;
    private final ObjectMapper objectMapper;

    /** roomId → (공유 구독 Disposable + 참조 수). 이 Pod 로컬. */
    private final Map<UUID, RoomSub> roomSubs = new ConcurrentHashMap<>();

    private record RoomSub(Disposable disposable, AtomicInteger refs) {
    }

    public ChatWebSocketHandler(RoomTokenVerifier verifier, EntryGate entryGate, ChatSessionRegistry registry,
            SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber) {
        this.verifier = verifier;
        this.entryGate = entryGate;
        this.registry = registry;
        this.sendUseCase = sendUseCase;
        this.subscriber = subscriber;
        // createdAt(Instant)을 epoch 숫자가 아니라 ISO-8601 문자열로 직렬화(프론트 계약)
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID roomId;
        String token;
        try {
            var params = UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri()).build().getQueryParams();
            roomId = UUID.fromString(params.getFirst("roomId"));
            token = params.getFirst("token");
        } catch (Exception e) {
            return session.close(CloseStatus.POLICY_VIOLATION);   // 잘못된 쿼리 — 상세 미노출
        }

        return verifier.verify(token, roomId)
                .flatMap(session1 -> entryGate.verify(session1).thenReturn(session1))
                .flatMap(chatSession -> runSession(session, chatSession))
                .onErrorResume(EntryDeniedException.class, e -> denyAndClose(session, e.reason()))
                .onErrorResume(WebSocketAuthException.class, e -> session.close(CloseStatus.POLICY_VIOLATION));
    }

    private Mono<Void> runSession(WebSocketSession session, ChatSession chatSession) {
        String sid = session.getId();
        UUID roomId = chatSession.roomId();
        acquireRoom(roomId);

        // Flux.defer: outbound()를 구독 시점(=register 완료 후)에 평가 — 즉시 평가하면 아직 미등록이라 Flux.empty()가 됨
        Mono<Void> outbound = session.send(
                Flux.defer(() -> registry.outbound(sid)).map(message -> session.textMessage(serialize(message))));

        Mono<Void> inbound = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> onInbound(sid, chatSession, payload))
                .then();

        return registry.register(sid, chatSession)
                .then(registry.getActiveCount(roomId))
                .flatMap(count -> registry.sendToSession(sid, roomInfo(count, chatSession.isRoomOwner())))
                // firstWithSignal: 둘 중 먼저 끝나는 쪽에 종료 — 클라 disconnect(inbound 완료)뿐 아니라
                // 강제 close(KICK/ROOM_ENDED → sink complete → outbound 완료) 시에도 WS를 즉시 닫는다.
                .then(Mono.firstWithSignal(outbound, inbound))
                .doFinally(signal -> cleanup(roomId, sid));
    }

    private Mono<Void> onInbound(String sid, ChatSession chatSession, String payload) {
        InboundMessage in;
        try {
            in = objectMapper.readValue(payload, InboundMessage.class);
        } catch (Exception e) {
            return registry.sendToSession(sid, error("VALIDATION", null));   // 파싱 실패 — clientMsgId 알 수 없음
        }
        return sendUseCase.send(buildCommand(chatSession, in))
                .flatMap(view -> registry.sendToSession(sid, toAck(view, in.clientMsgId())))
                .onErrorResume(e -> registry.sendToSession(sid, toError(e, in.clientMsgId())));
    }

    // ── 순수 변환 (단위 테스트 진입점) ──

    SendChatCommand buildCommand(ChatSession s, InboundMessage in) {
        return new SendChatCommand(
                s.roomId(), s.userId(), s.role().name(), s.isRoomOwner(),
                true,                       // isRoomAlive — 세션 플래그(ROOM_ENDED 수신 시 off). 현재 종료=세션 close로 처리, flip은 #54
                s.nickname(), s.email(),
                in.type(), in.content(), in.clientMsgId());
    }

    OutboundMessage toAck(ChatMessageView view, String clientMsgId) {
        return new OutboundMessage("ACK", null, view.id(), null, null, null, null, null, null,
                view.createdAt(), null, null, null, clientMsgId, null);
    }

    OutboundMessage toError(Throwable e, String clientMsgId) {
        if (e instanceof ChatRateLimitException rle) {
            return new OutboundMessage("RATE_LIMIT", null, null, null, null, null, null, null, null,
                    null, null, null, rle.getRetryAfterSeconds(), clientMsgId, null);
        }
        return error(errorCode(e), clientMsgId);
    }

    private OutboundMessage error(String code, String clientMsgId) {
        return new OutboundMessage("ERROR", code, null, null, null, null, null, null, null,
                null, null, null, null, clientMsgId, null);
    }

    private String errorCode(Throwable e) {
        if (e instanceof LiveNotActiveException) {
            return "NOT_ACTIVE";
        }
        if (e instanceof ChatPermissionDeniedException) {
            return "PERMISSION";
        }
        if (e instanceof UserKickedException) {
            return "KICKED";
        }
        if (e instanceof IllegalArgumentException) {
            return "VALIDATION";
        }
        return "INTERNAL";
    }

    OutboundMessage roomInfo(long activeCount, boolean isRoomOwner) {
        return new OutboundMessage("ROOM_INFO", null, null, null, null, null, null, null, null,
                null, null, activeCount, null, null, isRoomOwner);
    }

    // ── 구독 ref-count ──

    void acquireRoom(UUID roomId) {
        roomSubs.compute(roomId, (key, existing) -> {
            if (existing == null) {
                return new RoomSub(subscriber.subscribeRoom(roomId), new AtomicInteger(1));
            }
            existing.refs().incrementAndGet();
            return existing;
        });
    }

    void releaseRoom(UUID roomId) {
        roomSubs.computeIfPresent(roomId, (key, existing) -> {
            if (existing.refs().decrementAndGet() <= 0) {
                existing.disposable().dispose();
                return null;   // 마지막 퇴장 → 구독 해제
            }
            return existing;
        });
    }

    boolean isSubscribed(UUID roomId) {
        return roomSubs.containsKey(roomId);
    }

    private void cleanup(UUID roomId, String sid) {
        registry.unregister(roomId, sid).subscribe();
        releaseRoom(roomId);
    }

    private Mono<Void> denyAndClose(WebSocketSession session, EntryDeniedException.Reason reason) {
        OutboundMessage system = new OutboundMessage("SYSTEM", systemCode(reason), null,
                ChatConstants.SYSTEM_SENDER_ID, SYSTEM_NICKNAME, null, null, null, null,
                null, null, null, null, null, null);
        return session.send(Mono.just(session.textMessage(serialize(system))))
                .then(session.close(CloseStatus.POLICY_VIOLATION));
    }

    private String systemCode(EntryDeniedException.Reason reason) {
        return reason == EntryDeniedException.Reason.BANNED
                ? SystemMessageCode.BANNED.name()
                : SystemMessageCode.KICKED.name();
    }

    private String serialize(OutboundMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("OutboundMessage 직렬화 실패", e);   // 평탄 record라 실제로는 발생 안 함
        }
    }
}
