package com.sapari.streamingapp.websocket;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
 * <p>흐름: roomId(쿼리) + 룸 토큰(Sec-WebSocket-Protocol 헤더) 파싱 → 룸 토큰 검증({@link RoomTokenVerifier}) → 입장 게이트({@link EntryGate})
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
    /** 룸 토큰을 실어 나르는 서브프로토콜 이름. 클라는 ["bearer", <token>] 두 개를 제시한다. */
    private static final String TOKEN_SUBPROTOCOL = "bearer";
    private static final String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    /** 종료 결정 후 정상 클라가 버퍼에 남은 메시지를 받아갈 시간. 이 시간이 지나면 강제로 끊는다. */
    private static final Duration FORCED_CLOSE_GRACE = Duration.ofSeconds(3);

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

    // getSubProtocols()는 의도적으로 비운다(응답에 subprotocol echo 안 함). 서버가 offered subprotocol을
    // 응답에 실으면 클라 handshaker가 "기대 목록에 없다"며 거부하는 구현이 있어(Reactor Netty) 호환성 위험.
    // no-echo는 브라우저·Netty 모두 허용된다. 토큰은 아래 extractToken이 요청 헤더에서 직접 읽는다.

    /** Sec-WebSocket-Protocol 헤더에서 룸 토큰 추출 — 클라 제시 ["bearer", token] 중 "bearer"가 아닌 값. */
    private String extractToken(WebSocketSession session) {
        List<String> values = session.getHandshakeInfo().getHeaders().get(SEC_WEBSOCKET_PROTOCOL);
        if (values == null) {
            return null;
        }
        return values.stream()
                .flatMap(v -> Arrays.stream(v.split(",")))
                .map(String::trim)
                .filter(p -> !p.isEmpty() && !p.equalsIgnoreCase(TOKEN_SUBPROTOCOL))
                .findFirst()
                .orElse(null);
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UUID roomId;
        String token;
        try {
            var params = UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri()).build().getQueryParams();
            roomId = UUID.fromString(params.getFirst("roomId"));
            // 토큰(email PII 포함)은 쿼리 아닌 Sec-WebSocket-Protocol 헤더로 — access log·Referer·브라우저 히스토리 잔류 방지
            token = extractToken(session);
        } catch (Exception e) {
            return session.close(CloseStatus.POLICY_VIOLATION);   // 잘못된 핸드셰이크 — 상세 미노출
        }
        if (token == null) {
            return session.close(CloseStatus.POLICY_VIOLATION);
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
                // 채팅 프레임은 TEXT뿐이다. BINARY·PONG까지 파싱에 넣으면 모바일 keepalive 한 번에
                // ERROR(VALIDATION) 한 번씩 되돌려주게 된다(Ping은 Netty가 자동 응답하고 올려주지 않음).
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> onInbound(sid, chatSession, payload))
                .then();

        // 강제 종료 경로. sink complete가 버퍼에 막혀 outbound가 끝나지 않는 클라(소켓을 읽지 않는 경우)를
        // 위한 것이라, 정상 클라가 남은 메시지를 다 받고 스스로 끝낼 시간을 준 뒤에야 발화한다.
        Mono<Void> forcedClose = Mono.defer(() -> registry.terminationSignal(sid))
                .delayElement(FORCED_CLOSE_GRACE)
                .then();

        // 종료 사유를 코드로 실어 닫는다 — complete 신호엔 코드를 못 싣기 때문에 따로 붙인다.
        //
        // firstWithSignal 바깥이 아니라 서버발 종료 브랜치 "안"에 둔다: 승자가 신호를 내보내기 전에
        // 패자가 먼저 취소되는데, 그때 취소되는 session.receive()가 Reactor Netty에서 상태코드 없는
        // close 프레임을 먼저 내보내고 "이미 닫음" 래치를 걸어버린다. 바깥에 두면 그 뒤에 실행돼
        // 아무 효과가 없다(클라가 받는 코드는 1005).
        Mono<Void> closeWithReason = Mono.defer(() -> session.close(registry.closeStatusOf(sid)));

        return registry.register(sid, chatSession)
                .then(registry.getActiveCount(roomId))
                .flatMap(count -> registry.sendToSession(sid, roomInfo(count, chatSession.isRoomOwner())))
                // firstWithSignal: 셋 중 먼저 끝나는 쪽에 종료 — 클라 disconnect(inbound), 정상 종료
                // (sink complete → outbound), 그리고 그 둘이 다 막혔을 때의 강제 종료.
                // 클라가 먼저 끊은 경우(inbound)는 닫을 소켓이 이미 없으므로 코드를 붙이지 않는다.
                .then(Mono.firstWithSignal(outbound.then(closeWithReason), inbound,
                        forcedClose.then(closeWithReason)))
                .doFinally(signal -> cleanup(roomId, sid));
    }

    /** 인바운드 1건 처리 — 어떤 입력이 와도 ERROR 응답으로 끝내고 연결은 유지한다. (단위 테스트 진입점) */
    Mono<Void> onInbound(String sid, ChatSession chatSession, String payload) {
        // 종료가 확정된 세션은 소켓이 닫히기 전이라도 더 받지 않는다 — 그 창에 강퇴된 사용자가 계속
        // 보낼 수 있고, 그걸 막는 건 전송 경로의 Redis 조회뿐인데 그건 장애 시 통과(fail-open)한다.
        if (registry.isTerminating(sid)) {
            return Mono.empty();
        }
        InboundMessage in;
        try {
            in = objectMapper.readValue(payload, InboundMessage.class);
        } catch (Exception e) {
            return registry.sendToSession(sid, error("VALIDATION", null));   // 파싱 실패 — clientMsgId 알 수 없음
        }
        // JSON 리터럴 null은 예외 없이 null로 파싱돼 위 catch를 그냥 통과한다.
        if (in == null) {
            return registry.sendToSession(sid, error("VALIDATION", null));
        }
        // 에러 응답이 in을 다시 건드리지 않게 미리 뽑아둔다 — 폴백이 예외를 던지면 그 예외가 곧장
        // downstream onError가 되어 인바운드 스트림이 죽는다(에러 핸들러는 절대 실패하면 안 되는 자리).
        String clientMsgId = in.clientMsgId();
        // defer로 감싸 커맨드 생성(신뢰경계 검증)의 throw까지 onError 신호로 만든다 — 감싸지 않으면
        // 인자 평가 위치에서 터져 아래 onErrorResume을 지나치고, 인바운드 스트림이 죽어 연결이 끊긴다.
        return Mono.defer(() -> sendUseCase.send(buildCommand(chatSession, in)))
                .flatMap(view -> registry.sendToSession(sid, toAck(view, clientMsgId)))
                .onErrorResume(e -> registry.sendToSession(sid, toError(e, clientMsgId)));
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
