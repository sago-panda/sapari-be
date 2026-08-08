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
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
    /**
     * close 프레임 flush를 기다리는 상한. 넘기면 앱 자원을 정리하고 <b>소켓도 직접 내린다</b>.
     *
     * <p>close는 write future에 채널 닫기를 걸어두는 구조라, 그 write가 flush되지 않으면 채널이 남는다.
     * 이후 다시 닫으려 해도 "이미 close 보냄" 표시 때문에 무동작이라, 소켓을 열어둔 채 수신만 멈춘 클라는
     * 커넥션이 계속 유지됐다. 그래서 이 상한을 넘기면 커넥션을 되찾아 끊는다({@code forceDispose}).
     */
    private static final Duration CLOSE_FLUSH_TIMEOUT = Duration.ofSeconds(5);

    private final RoomTokenVerifier verifier;
    private final NettyConnectionRegistry connections;
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
            SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber, NettyConnectionRegistry connections) {
        this.verifier = verifier;
        this.connections = connections;
        this.entryGate = entryGate;
        this.registry = registry;
        this.sendUseCase = sendUseCase;
        this.subscriber = subscriber;
        // createdAt(Instant)을 epoch 숫자가 아니라 ISO-8601 문자열로 직렬화(프론트 계약)
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // 모르는 필드는 흘려보낸다. 계약에 있는 필드는 그대로 엄격히 검증하되, 클라가 자기 필드를
                // 하나 얹었다고 파싱이 통째로 실패해서는 안 된다 — 그 실패는 거부 응답 솎기 대상이 되어
                // 뒤따르는 정상 프레임의 응답까지 함께 삼킨다. 오타 필드도 조용히 넘어가지 않는다:
                // content가 비면 내용 검증에, clientMsgId가 비면 필수 검증에 걸려 ERROR로 돌아간다.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
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
                // concatMap이다 — flatMap의 기본 동시성은 256이라, 한 세션이 프레임을 파이프라이닝하면
                // 최대 256건이 응답을 기다리지 않고 한꺼번에 안으로 들어간다. 그러면 아래 레이트리밋 로컬 창이
                // 무력해진다: 창은 첫 거부가 돌아온 뒤에야 무장되는데 그 시점엔 이미 다 통과한 뒤다.
                // 한 세션의 인바운드를 병렬로 처리해서 얻는 것도 없다 — 오히려 저장·중계 순서가 전송 순서와
                // 어긋난다(레이트리밋이 면제되는 방 주인에게서 실제로 드러난다).
                .concatMap(payload -> onInbound(sid, chatSession, payload))
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
        // close 프레임도 결국 소켓 write라, 읽지 않는 클라에선 앞선 잔량 뒤에 줄 서서 완료되지 않을 수 있다.
        // 그 상태로 두면 아래 firstWithSignal이 안 끝나 doFinally(cleanup)까지 막힌다 — 세션 명부·방 구독·
        // Redis 항목이 통째로 남는다. 상한을 둬서 그 셋은 반드시 회수하되, 채널 자체는 남을 수 있다
        // (CLOSE_FLUSH_TIMEOUT 주석 참고).
        // cache(): 아래 firstWithSignal의 두 브랜치가 같은 Mono를 참조한다. 둘이 근소한 차로 진행하면
        // 구독이 두 번 일어나 close도 두 번 시도된다. 실제로는 두 번째가 no-op이지만, 그건 netty가
        // "이미 닫음" 래치를 거는 덕이라 우리 코드의 보장이 아니다. 여기서 한 번만 돌게 못 박는다.
        Mono<Void> closeWithReason = closeWithReason(session, sid).cache();

        return registry.register(sid, chatSession)
                .then(roomInfoFor(roomId, chatSession))
                .flatMap(info -> registry.sendToSession(sid, info))
                // firstWithSignal: 셋 중 먼저 끝나는 쪽에 종료 — 클라 disconnect(inbound), 정상 종료
                // (sink complete → outbound), 그리고 그 둘이 다 막혔을 때의 강제 종료.
                // 클라가 먼저 끊은 경우(inbound)는 닫을 소켓이 이미 없으므로 코드를 붙이지 않는다.
                .then(Mono.firstWithSignal(outbound.then(closeWithReason), inbound,
                        forcedClose.then(closeWithReason)))
                .doFinally(signal -> cleanup(roomId, sid));
    }

    /**
     * 종료 사유를 코드로 실어 닫는다. flush가 막혀 close가 끝나지 않아도 상한 뒤 완료해
     * 뒤따르는 정리(doFinally)를 진행시킨다. (테스트 진입점)
     */
    Mono<Void> closeWithReason(WebSocketSession session, String sid) {
        return Mono.defer(() -> session.close(registry.closeStatusOf(sid)))
                .timeout(CLOSE_FLUSH_TIMEOUT, Mono.fromRunnable(() -> forceDispose(session)));
    }

    /**
     * close 프레임이 상한 안에 나가지 못하면 소켓을 직접 내린다.
     *
     * <p>여기까지 왔다는 건 그 프레임이 나갈 수 없다는 뜻이다 — 더 기다려도 달라지지 않고, 그대로 두면
     * 앱 자원은 정리됐는데 커넥션만 남는다. 반복하면 파일 디스크립터가 바닥난다.
     */
    private void forceDispose(WebSocketSession session) {
        if (session instanceof ReactorNettyWebSocketSession netty) {
            connections.dispose(netty.getChannelId());
        }
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
            return rejectMalformed(sid);
        }
        // JSON 리터럴 null은 예외 없이 null로 파싱돼 위 catch를 그냥 통과한다.
        if (in == null) {
            return rejectMalformed(sid);
        }
        // 상관관계 id는 되돌려주되 길이는 잘라서 싣는다 — 검증 전 값이라 프레임 한도까지 클 수 있고,
        // 그대로 실으면 거부 응답이 그 크기를 그대로 되돌려준다(1:1 증폭). 짝짓기엔 계약 길이면 충분하다.
        // 에러 응답이 in을 다시 건드리지 않게 미리 뽑아둔다 — 폴백이 예외를 던지면 그 예외가 곧장
        // downstream onError가 되어 인바운드 스트림이 죽는다(에러 핸들러는 절대 실패하면 안 되는 자리).
        String clientMsgId = truncateForEcho(in.clientMsgId());
        // defer로 감싸 커맨드 생성(신뢰경계 검증)의 throw까지 onError 신호로 만든다 — 감싸지 않으면
        // 인자 평가 위치에서 터져 아래 onErrorResume을 지나치고, 인바운드 스트림이 죽어 연결이 끊긴다.
        // 레이트리밋 창 안이면 Redis에 다시 묻지 않는다 — 답을 이미 안다. 묻는 비용이 왕복 3회(강퇴 조회·
        // SET NX·잔여 TTL)라, 회선 속도로 미는 클라 하나가 그대로 Redis 부하가 된다. 창이 끝나면 평소대로 묻는다.
        long retryAfter = registry.rateLimitRetryAfterSeconds(sid);
        if (retryAfter > 0) {
            return respondRejected(sid, rateLimited(retryAfter, clientMsgId));
        }
        return Mono.defer(() -> sendUseCase.send(buildCommand(chatSession, in)))
                .flatMap(view -> registry.sendToSession(sid, toAck(view, clientMsgId)))
                .onErrorResume(e -> {
                    if (e instanceof ChatRateLimitException rle) {
                        registry.recordRateLimited(sid, rle.getRetryAfterSeconds());
                    }
                    if (e instanceof UserKickedException) {
                        // 여기까지 왔다 = 이 Pod가 강퇴 신호를 못 받았다(받았으면 이미 닫혀 위에서 걸린다).
                        // 방금 권위 있게 확인했으니 닫는다 — 안 닫으면 계속 읽으면서 프레임마다 강퇴 조회를 태운다.
                        //
                        // 순서가 중요하다. 종료를 먼저 걸면 sink가 닫혀 뒤이은 사유 프레임이 FAIL_TERMINATED로
                        // 조용히 사라진다 — 클라는 1008만 받고 무엇 때문인지, 어느 메시지가 실패했는지 모른다.
                        // 그래서 사유를 먼저 실어 보내고, 성공하든 실패하든 반드시 닫는다(doFinally).
                        //
                        // 솎기도 거치지 않는다. 솎기는 되돌림이 반복될 때 비용이 되기 때문인데, 강퇴는 그 자리에서
                        // 세션을 끝내므로 연결당 한 번뿐이다. 반복이 없으니 증폭도 없고, 우회로가 되지도 않는다.
                        return registry.sendToSession(sid, toError(e, clientMsgId))
                                .doFinally(signal -> registry.terminateKicked(sid));
                    }
                    return respondRejected(sid, toError(e, clientMsgId));
                });
    }

    /**
     * 파싱 불가 프레임에 ERROR로 답한다. clientMsgId는 알 수 없어 싣지 못한다.
     */
    private Mono<Void> rejectMalformed(String sid) {
        return respondRejected(sid, error("VALIDATION", null));
    }

    /**
     * 거부 응답을 솎아가며 보낸다.
     *
     * <p>레이트리밋은 커맨드가 만들어진 뒤에야 동작하므로, 그 앞에서 떨어지는 프레임은 답해주는 만큼
     * 그대로 되돌아온다. 그 되돌림을 <b>사유를 가리지 않고</b> 솎아낸다 — 예외를 두는 순간 그게 우회로가
     * 되기 때문이다. 첫 거부는 반드시 답하므로 정상 클라는 무엇이 틀렸는지 그대로 알 수 있다.
     *
     * <p>솎인 프레임은 <b>아무 응답도 받지 못한다.</b> 클라가 낙관적으로 그린 말풍선을 되돌릴 신호가
     * 없다는 뜻이라, 프론트는 응답 없음을 성공으로 읽지 말고 자체 타임아웃으로도 정리해야 한다.
     * 유일한 예외는 강퇴다 — 연결당 한 번뿐이라 반복 비용이 없어 그대로 답한다.
     */
    private Mono<Void> respondRejected(String sid, OutboundMessage response) {
        return registry.shouldReplyToRejection(sid)
                ? registry.sendToSession(sid, response)
                : Mono.empty();
    }

    // ── 순수 변환 (단위 테스트 진입점) ──

    SendChatCommand buildCommand(ChatSession s, InboundMessage in) {
        return new SendChatCommand(
                s.roomId(), s.userId(), s.role().name(), s.isRoomOwner(),
                // isRoomAlive — 평상시엔 세션이 살아있다는 것 자체가 근거다. 입장에서 종료 마커를 검사하고,
                // 접속 중에 방이 끝나면 종료 처리가 세션을 닫아 위 isTerminating에서 걸린다.
                //
                // 남는 구간이 있고, 그 크기를 정확히 적어둔다. 종료 신호는 무영속 Pub/Sub이라 그 순간 구독이
                // 끊긴 Pod는 통째로 놓친다. 그 Pod의 세션은 닫히지 않고 이 값이 상수 true라, 종료된 방에
                // <b>소켓이 살아있는 내내</b> 전송이 통과해 이력에 남는다 — 신규 입장과 달리 토큰 수명으로
                // 유계가 아니다.
                //
                // 매 전송마다 마커를 읽으면 이 구간은 실제로 막힌다. 마커는 이벤트를 받은 아무 Pod나 쓰므로,
                // 한 Pod만 신호를 놓친 부분 장애에서는 마커가 실재하기 때문이다. 그래도 안 읽는 건 못 막아서가
                // 아니라 <b>메시지마다 Redis 왕복 1회가 비싸서</b>다. 주기적 재검사로 창을 유계화하는 건
                // 별도 과제로 남긴다. 유실 자체를 닫는 건 종료 이벤트를 영속 전달로 바꾸는 쪽(live 소유)이다.
                true,
                s.nickname(), s.email(),
                in.type(), in.content(), in.clientMsgId());
    }

    OutboundMessage toAck(ChatMessageView view, String clientMsgId) {
        return new OutboundMessage("ACK", null, view.id(), null, null, null, null, null, null,
                view.createdAt(), null, null, null, clientMsgId, null);
    }

    /** 거부 응답에 되돌려 실을 만큼만 남긴다. 계약 상한(64자)을 넘는 값은 어차피 거부된다. */
    private String truncateForEcho(String clientMsgId) {
        int max = ChatConstants.MAX_CLIENT_MSG_ID_LENGTH;
        return clientMsgId == null || clientMsgId.length() <= max ? clientMsgId : clientMsgId.substring(0, max);
    }

    /** 레이트리밋 응답 — 서비스가 준 것이든 로컬 창에서 만든 것이든 같은 모양이어야 한다. */
    private OutboundMessage rateLimited(long retryAfterSeconds, String clientMsgId) {
        return new OutboundMessage("RATE_LIMIT", null, null, null, null, null, null, null, null,
                null, null, null, retryAfterSeconds, clientMsgId, null);
    }

    OutboundMessage toError(Throwable e, String clientMsgId) {
        if (e instanceof ChatRateLimitException rle) {
            return rateLimited(rle.getRetryAfterSeconds(), clientMsgId);
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

    /**
     * 입장 시 보낼 ROOM_INFO. 시청자 수 조회가 실패해도 <b>접속은 성립시킨다</b> — 그 수는 표시용이고,
     * 출처인 Redis HASH에 메시지 전달·강퇴·종료 어느 것도 의존하지 않는다. 강퇴 조회조차 실패 시
     * 입장을 허용하는 정책(EntryGate)과 같은 방향이다. (테스트 진입점)
     */
    Mono<OutboundMessage> roomInfoFor(UUID roomId, ChatSession chatSession) {
        return registry.getActiveCount(roomId)
                .map(count -> roomInfo(count, chatSession.isRoomOwner()))
                .onErrorResume(e -> {
                    log.warn("활성 시청자 수 조회 실패 — 수 없이 입장 진행 roomId={} cause={}",
                            roomId, e.getClass().getSimpleName());
                    return Mono.just(roomInfo(null, chatSession.isRoomOwner()));
                });
    }

    /** activeCount가 null이면 "알 수 없음" — 조회 실패 시 0 같은 거짓값 대신 비워 보낸다. */
    OutboundMessage roomInfo(Long activeCount, boolean isRoomOwner) {
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

    /** 거부 사유를 클라가 렌더할 code로. switch 식이라 Reason이 늘면 컴파일이 막는다(조용한 오매핑 방지). */
    private String systemCode(EntryDeniedException.Reason reason) {
        return switch (reason) {
            case KICKED -> SystemMessageCode.KICKED.name();
            case BANNED -> SystemMessageCode.BANNED.name();
            case ROOM_ENDED -> SystemMessageCode.ROOM_ENDED.name();
        };
    }

    private String serialize(OutboundMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("OutboundMessage 직렬화 실패", e);   // 평탄 record라 실제로는 발생 안 함
        }
    }
}
