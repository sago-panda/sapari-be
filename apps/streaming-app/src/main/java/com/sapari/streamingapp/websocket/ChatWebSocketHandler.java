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
import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.global.logging.LogFields;
import com.sapari.chat.application.port.DeliveryStage;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.exception.UserBannedException;
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
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SignalType;

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
    private final SystemMessageService systemMessageService;
    private final ObjectMapper objectMapper;

    /**
     * 연결 하나에 걸리는 시간 상한 셋. 설정으로 받는 이유는 테스트가 짧게 주기 때문이다 — 상수로
     * 두면 이 분기들이 <b>배선됐는지</b>를 재는 수단이 가상 시간뿐이고, 가상 시간은 분기만 재고
     * 배선은 재지 못한다. 특히 ping 에 대한 pong 이 인바운드로 돌아오는지는 실제 소켓으로만 확인된다.
     */
    private final SessionTimeouts timeouts;

    /**
     * 관측 포트. ping 드롭은 메시지 유실이 아니지만, 꾸준히 오르면 그 세션의 아웃바운드가 밀려 있다는
     * 뜻이고 생존 판정이 늦어진다 — 설정(ping 간격 : 생존 기한)의 비가 조용히 깨졌는지를 보는 값이다.
     */
    private final ChatMetrics metrics;

    /** roomId → (공유 구독 Disposable + 참조 수). 이 Pod 로컬. */
    private final Map<UUID, RoomSub> roomSubs = new ConcurrentHashMap<>();

    private record RoomSub(Disposable disposable, AtomicInteger refs) {
    }

    public ChatWebSocketHandler(RoomTokenVerifier verifier, EntryGate entryGate, ChatSessionRegistry registry,
            SendChatUseCase sendUseCase, ChatBroadcastSubscriber subscriber, NettyConnectionRegistry connections,
            SystemMessageService systemMessageService, SessionTimeouts timeouts, ChatMetrics metrics) {
        this.metrics = metrics;
        this.timeouts = timeouts;
        this.verifier = verifier;
        this.connections = connections;
        this.entryGate = entryGate;
        this.registry = registry;
        this.sendUseCase = sendUseCase;
        this.subscriber = subscriber;
        this.systemMessageService = systemMessageService;
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

        // 상관 식별자를 Reactor Context 에 싣는다. MDC 로는 안 된다 — ThreadLocal 이라 Reactor 의
        // 스레드 전환을 넘지 못하고, 특히 <b>onErrorResume 안에서 null</b> 이다(실측). 이 앱의 열화
        // 로그가 거의 전부 그 람다 안이라, 가장 알고 싶은 줄이 가장 확실하게 식별자를 잃고 있었다.
        //
        // request_id 를 따로 두지 않는다. 서블릿 쪽은 요청이 수명 단위지만 여기서는 <b>세션</b>이고,
        // session.getId() 가 핸드셰이크 첫 줄부터 있다. 핸드셰이크만 덮는 식별자를 하나 더 만들면
        // 같은 것을 가리키는 축이 둘이 되고, 둘 중 어느 것으로 묶어야 하는지가 흐려진다.
        //
        // user_id 는 안쪽에 따로 싣는다 — 검증 전에는 "이 사람이 누구인지" 가 아직 서버 판단이 아니다.
        // 게이트가 열린 로그에 user_id 가 없는 것은 누락이 아니라 그 시점의 사실이다(게이트 로그
        // 문장 자체는 대상 userId 를 싣는다).
        return verifier.verify(token, roomId)
                .flatMap(session1 -> entryGate.verify(session1).thenReturn(session1))
                .flatMap(chatSession -> runSession(session, chatSession)
                        .contextWrite(context -> context.put(LogFields.USER_ID,
                                chatSession.userId().toString())))
                .onErrorResume(EntryDeniedException.class, e -> denyAndClose(session, e.reason()))
                .onErrorResume(WebSocketAuthException.class, e -> session.close(CloseStatus.POLICY_VIOLATION))
                .contextWrite(context -> context
                        .put(LogFields.SESSION_ID, session.getId())
                        .put(LogFields.ROOM_ID, roomId.toString()));
    }

    /**
     * 세션 하나의 수명 전체. (테스트 진입점 — 수명 상한 분기가 여기서 배선되므로 그 배선을 재려면
     * 이 메서드를 직접 구동해야 한다. 분기 자체만 따로 재면 배선이 빠진 것을 아무도 묻지 않는다.)
     */
    Mono<Void> runSession(WebSocketSession session, ChatSession chatSession) {
        String sid = session.getId();
        UUID roomId = chatSession.roomId();
        acquireRoom(roomId);

        // Flux.defer: outbound()를 구독 시점(=register 완료 후)에 평가 — 즉시 평가하면 아직 미등록이라 Flux.empty()가 됨
        // 아웃바운드에 ping 을 합류시킨다. 별도 send 로 두면 같은 소켓에 두 publisher 가 붙어 프레임
        // 순서 보장이 사라진다 — Reactor Netty 의 send 는 한 publisher 안에서만 직렬화한다.
        //
        // ⚠️ <b>ping 은 데이터 채널이 닫힐 때 함께 멈춰야 한다.</b> {@code Flux.interval} 은 스스로
        // 끝나지 않으므로 그대로 합류시키면 합쳐진 publisher 가 영원히 완료되지 않고, 그러면
        // {@code session.send} 도 완료되지 않아 아래 {@code outbound.then(closeWithReason)} 가
        // <b>도달 불가</b>가 된다. 그 결과 서버발 종료(강퇴·밴·방 종료·수명·생존)가 유예 경로로만
        // 닫혀 27ms 가 3초가 된다(실측 — 리뷰어가 세 번, 내가 한 번).
        //
        // <b>버퍼 초과(1013)는 그 목록에 들지 않는다 — 수정 전에도 후에도 유예 경로다.</b> 1013 이
        // 발생하는 조건이 "클라가 256 슬롯을 비우지 않는다"인데, 그것이 곧 데이터 스트림이 종료를
        // 전달하지 못하는 조건이다. 큐가 비어야 unicast 가 onComplete 를 내고 그때서야 아래
        // {@code doFinally} 가 돌기 때문이다. 즉 이 빠른 경로가 1013 에는 원리적으로 닿지 않는다
        // (리뷰어가 동형 replica 로 실측 — 버퍼가 찬 상태에서 tryEmitComplete=OK 가 와도 merge 가
        // 완료되지 않는다). 버스트로 한 번 넘쳤다가 따라잡는 클라라면 빠른 경로를 탈 수 있지만,
        // 1013 이 존재하는 이유인 "읽기를 멈춘 클라"는 탈 수 없다.
        //
        // 제어 채널이 아니라 <b>데이터 스트림의 종료</b>에 묶는다.
        //
        // ⚠️ 한때 그 근거를 "제어 채널 발화가 실패하면 어느 분기도 이기지 못한다"로 적었는데
        // <b>그 전제가 도달 불가다.</b> Sinks.one() 은 SinkOneSerialized 이고 첫 tryEmitValue 는
        // 구독자가 없어도, 구독자가 취소한 뒤에도 OK 를 돌려준다(실측). 실패는 2회차
        // (FAIL_TERMINATED)와 동시 발화(FAIL_NON_SERIALIZED)뿐인데, 레지스트리가 closeStatus CAS 로
        // 발화를 1회·1스레드로 고정하고 호출지가 한 곳이라 둘 다 생기지 않는다.
        //
        // 성립하는 근거는 다른 것이다: {@code registry.outbound(sid)} 가 {@code Flux.empty()} 를
        // 돌려주는 경우 — 구독 시점에 그 세션이 이미 사라졌을 때다. 데이터 쪽에 묶으면 그 빈 스트림이
        // 곧 {@code doFinally} 를 돌려 {@code takeUntilOther} 가 ping 을 완료시키고 merge 가 끝나
        // 세션이 닫힌다. 제어 채널에 묶으면 {@code terminationSignal} 이 모르는 세션에
        // {@code Mono.never()} 를 주므로 ping 이 영원히 돌고, 클라가 스스로 끊지 않으면 그 세션이 남는다.
        Sinks.Empty<Void> dataChannelDone = Sinks.empty();
        Flux<WebSocketMessage> data = Flux.defer(() -> registry.outboundFrames(sid))
                // 프레임이 와이어 문자열을 들고 있다 — 같은 팬아웃의 다른 세션이 이미 만들어 뒀으면
                // 그걸 쓴다. 방에 N 세션이면 N 번 직렬화하던 자리다(실측: 12,000 세션 9.23ms).
                .map(frame -> session.textMessage(frame.wire(this::serialize)))
                .doFinally(signal -> dataChannelDone.tryEmitEmpty());
        // 보는 것만 하는 시청자가 대다수라 발화를 생존 신호로 쓸 수 없다. 서버가 먼저 묻는다.
        // onBackpressureDrop: 요청이 없을 때 틱을 버린다. 안 버리면 prefetch 가 차는 순간 send 가
        // OverflowException 으로 끝나는데, 그 에러는 핸들러의 어느 onErrorResume 에도 안 걸린다.
        //
        // 한때 그 조건을 "PONG 은 답하지만 데이터 프레임은 읽지 않는 클라"로 적었는데 <b>그런 클라는
        // 없다</b> — PING·PONG 과 데이터가 같은 TCP 스트림이라 소켓을 비우지 않는 클라는 PING 도 못 본다.
        // 실제 조건은 "아웃바운드 전달이 32 틱 동안 0" 이고, 32 는 merge inner 의 prefetch
        // (Queues.XS_BUFFER_SIZE)다. 즉 영향 범위가 <b>ping 간격 × 32</b> 라 간격을 줄이면 그만큼 빨리
        // 닿는다(기본값 30s 에서는 16분이라 생존 기한 90s 가 10배 먼저 발화한다).
        Mono<Void> outbound = session.send(Flux.merge(data, pings(session, dataChannelDone.asMono())));

        Mono<Void> inbound = runInbound(session.receive()
                // 생존 시계는 필터 <b>앞에서</b> 민다 — 재려는 것이 "말을 하는가"가 아니라 "소켓이
                // 살아 있는가"라서, 서버 ping 에 대한 pong 처럼 파싱 대상이 아닌 프레임도 세어야 한다.
                // 여기서는 종류만 읽고 payload 는 건드리지 않는다. 필터에서 버려지는 프레임의 버퍼도
                // reactor-netty 가 해제하므로(FluxReceive 가 onNext 직후 try/finally 로 release)
                // 이 탭이 버퍼 수명에 영향을 주지 않는다 — 리뷰어가 원전으로 확인했다.
                //
                // <b>PONG 이 여기까지 올라온다는 것이 이 기능의 전제다</b>(실측). 올라오지 않는다면
                // 생존 확인이 "소켓이 살아 있는가" 대신 "클라가 말을 하는가"를 재게 되어, 보는 것만
                // 하는 시청자를 기한마다 전부 끊는다.
                //
                // ⚠️ 그 성질은 <b>Netty 기본값이 아니라 reactor-netty 의 것</b>이다. Netty 자신의
                // {@code WebSocketProtocolHandler} 는 무인자 생성자가 {@code dropPongFrames = true} 를
                // 넣어 <b>반대로</b> 동작한다. 성립하는 이유는 reactor-netty 가 그 핸들러를 쓰지 않고
                // 직접 핸드셰이크하며, 그 인바운드 경로에 PING 분기만 있고 PONG 분기가 없어서
                // PONG 이 일반 프레임 경로로 올라오기 때문이다({@code WebsocketServerSpec.handlePing()}
                // 기본값 false = PING 자동 응답). 그 핸들러를 파이프라인에 끼우거나 서버를 바꾸면
                // <b>이 기능이 조용히 죽는다.</b>
                .doOnNext(message -> registry.recordInbound(sid))
                // 채팅 프레임은 TEXT뿐이다. BINARY·PONG까지 파싱에 넣으면 모바일 keepalive 한 번에
                // ERROR(VALIDATION) 한 번씩 되돌려주게 된다(들어오는 PING 은 reactor-netty 가 자동
                // 응답하고 올려주지 않는다 — 위 문단의 귀속과 같다. PONG 은 올라오므로 위 탭이 센다).
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                .map(WebSocketMessage::getPayloadAsText), sid, chatSession);

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

        // 수명 상한. 상한에 닿으면 종료를 <b>촉발만</b> 하고 이 분기는 끝나지 않는다(Mono.never) —
        // 실제 닫기는 아래 셋이 이미 하는 일이고, 여기서 함께 완료되면 그 경로를 건너뛰어 버퍼에 남은
        // 메시지가 버려진다. 촉발이 전달되지 않는 경우도 유계다: 제어 채널이 발화하므로 forcedClose가
        // 유예 뒤에 받는다.
        // 지터를 섞는다. Mono.delay 는 구독 시점 기준이라, 같이 들어온 세션들(방송 시작 직후가 그렇다)이
        // 같은 순간에 같이 만료되고 같이 재접속한다. 재접속 1건이 live 의 입장(서명·검증·Redis 왕복)을
        // 다시 타는데 그 엔드포인트에 레이트리밋이 없다 — 12,000 세션이 한 점에 몰리면 그 자체가 부하다.
        // 상한을 늘리지 않고 아래로만 흩는다(상한이 통제 값이므로 넘기면 안 된다).
        Mono<Void> lifetimeExpiry = Mono.delay(jittered(timeouts.maxLifetime()))
                .doOnNext(ignored -> registry.terminateLifetimeExpired(sid))
                .then(Mono.never());

        // 생존 확인. 수명 상한과 달리 이쪽은 <b>죽은 소켓</b>을 거둔다 — 아무것도 보내지 않는 클라는
        // 정상이지만(시청만 하는 경우) 아무것도 <i>올리지 않는</i> 클라는 끊긴 소켓과 구별되지 않는다.
        // 끊긴 쪽을 거두지 않으면 그 세션의 sink·방 인덱스·Redis 명부가 수명 상한까지 남는다.
        // 첫 점검은 기한이 아니라 <b>기한 + 간격</b>이다. 생존 시계가 등록 시각으로 초기화되므로
        // (레지스트리의 register 참고), 기한에 바로 점검하면 첫 창에서만 유실 내성이 한 칸 짧아진다 —
        // 기본값에서 t=30s·60s 의 ping 이 둘 다 유실되면 t=90s 점검에서 last 가 아직 등록 시각이라
        // 90 - 0 >= 90 이 되어 <b>멀쩡한 시청자가 끊긴다.</b> "기한 = 3 × 간격 = 2회 내성" 이
        // 첫 창에서만 거짓이었다.
        //
        // ⚠️ 정상 구간의 그 2회 내성도 <b>넉넉하지는 않다.</b> PONG 이 두 번 연속 유실되면 점검
        // 시점의 {@code now - last} 가 정확히 90s 이고 판정이 {@code >=} 라, 살아남는 이유는
        // {@code last} 에 왕복시간이 더해져 있다는 것뿐이다. 즉 왕복 하나만큼의 마진이다 — 같은
        // 파일의 "기한 >= 간격 × 2" 불변식이 "왕복 하나에 기대지 말라" 를 근거로 세워져 있으니,
        // 내성을 더 벌리려면 기한을 간격의 3배보다 크게 잡는 것이 그 기준에 맞다. 이 수정이 고친
        // 첫 창 쪽은 간격 하나(30s)를 더 받아 그 문제가 없다.
        //
        // 대가는 죽은 소켓 수거가 간격 하나만큼 늦어지는 것이다(기본값 90s → 120s).
        Mono<Void> livenessWatch = Flux.interval(
                        timeouts.livenessDeadline().plus(timeouts.pingInterval()), timeouts.pingInterval())
                .filter(ignored -> registry.isUnresponsive(sid, timeouts.livenessDeadline()))
                .next()
                .doOnNext(ignored -> registry.terminateUnresponsive(sid))
                .then(Mono.never());

        return registry.register(sid, chatSession)
                .then(roomInfoFor(roomId, chatSession))
                .flatMap(info -> registry.sendToSession(sid, info))
                // firstWithSignal: 다섯 중 먼저 끝나는 쪽에 종료 — 클라 disconnect(inbound), 정상 종료
                // (sink complete → outbound), 그 둘이 다 막혔을 때의 강제 종료, 그리고 수명 상한·생존 확인.
                // 클라가 먼저 끊은 경우(inbound)는 닫을 소켓이 이미 없으므로 코드를 붙이지 않는다.
                .then(Mono.firstWithSignal(outbound.then(closeWithReason), inbound,
                        forcedClose.then(closeWithReason), lifetimeExpiry, livenessWatch))
                .doFinally(signal -> cleanup(roomId, sid));
    }

    /**
     * 생존을 묻는 ping 스트림. (테스트 진입점 — 수요가 없을 때의 동작을 재려면 직접 구독해야 한다)
     *
     * <p>데이터 채널이 닫히면 함께 멈춘다({@code takeUntilOther}) — 위 {@code runSession} 참고.
     */
    Flux<WebSocketMessage> pings(WebSocketSession session, Mono<Void> dataChannelDone) {
        return Flux.interval(timeouts.pingInterval(), timeouts.pingInterval())
                .onBackpressureDrop(ignored -> recordPingDrop())
                .map(ignored -> session.pingMessage(factory -> factory.wrap(new byte[0])))
                .takeUntilOther(dataChannelDone);
    }

    /**
     * 버린 ping 을 센다.
     *
     * <p><b>여기서는 던질 수 있는 호출을 하지 마라.</b> 이 메서드는 {@code onBackpressureDrop} 의
     * 콜백 안에서 돈다 — 던지면 reactor 가 그것을 {@code onError} 로 바꿔 ping 스트림을 종결시키고,
     * {@code merge} 를 통해 {@code outbound} 가 에러로 끝난다. 그러면 {@code outbound.then(closeWithReason)}
     * 이 에러를 그대로 전파해 <b>닫는 코드를 싣지 못하고</b> 세션이 사유 없이 끊긴다(코드 경로:
     * 그 분기에 {@code onErrorResume} 이 없다). {@code modules/chat/AGENTS.md} 가 같은 규율을
     * fail-open 핸들러에 대해 세워 뒀고 이것이 그 새 사례다.
     *
     * <p>한때 {@code onBackpressureDrop()} 을 인자 없이 썼다. 그러면 이 줄만 이 파일의 규율
     * ({@code droppedOnContention}·{@code lifetimeExpiredCount})에서 빠진다 — 세지도 로그되지도
     * 않으니, 이 줄을 통째로 지워도 테스트가 전부 초록이었다.
     *
     * <p>지금 드롭이 안 일어나는 것은 <b>설정 두 값의 비</b>에 기대고 있다. 드롭까지 필요한 정체가
     * ping 간격 × 32({@code Queues.XS_BUFFER_SIZE})이고 기본값에서 16분인데, 생존 기한 90s 가 10배
     * 먼저 세션을 거둔다. {@code ping-interval} 을 줄이거나 {@code liveness-deadline} 을 키우면 그
     * 여유가 조용히 사라진다 — 그때 숫자가 보여야 한다.
     */
    private void recordPingDrop() {
        metrics.deliveryDropped(DeliveryStage.LIVENESS_PING);
        long count = pingsDropped.incrementAndGet();
        if (shouldLogPingDrop()) {
            log.warn("ping 드롭 — 아웃바운드 수요가 없어 틱을 버렸다(누적 {}건). 생존 확인이 이 세션을 "
                    + "거두기 전이면 ping 간격·생존 기한의 비를 확인할 것", count);
        }
    }

    /** 드롭 로그 간격 — 몰리는 상황이 곧 포화라 건당 로그가 상황을 악화시킨다(레지스트리와 같은 규율). */
    private boolean shouldLogPingDrop() {
        long now = System.nanoTime();
        long last = lastPingDropLogNanos.get();
        return now - last >= PING_DROP_LOG_INTERVAL_NANOS
                && lastPingDropLogNanos.compareAndSet(last, now);
    }

    private static final long PING_DROP_LOG_INTERVAL_NANOS = Duration.ofSeconds(10).toNanos();

    /** 버린 ping 누적 수. (테스트 진입점) */
    long pingsDropped() {
        return pingsDropped.get();
    }

    private final java.util.concurrent.atomic.AtomicLong pingsDropped =
            new java.util.concurrent.atomic.AtomicLong();

    private final java.util.concurrent.atomic.AtomicLong lastPingDropLogNanos =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() - PING_DROP_LOG_INTERVAL_NANOS);

    /**
     * 수명 상한을 <b>아래로</b> {@value SessionTimeouts#LIFETIME_JITTER_PERCENT}% 안쪽에서 흩는다. (테스트 진입점)
     *
     * <p><b>아래로만 흩는다.</b> 이 값은 "접속 후 바뀐 권위가 반영되기까지의 상한"이라 넘기면 통제가
     * 느슨해진다. 그래서 {@code [limit × (1 - p), limit]} 범위다.
     *
     * <p>보안 난수가 아니다 — 흩는 것이 목적이고 예측 불가가 목적이 아니다.
     */
    Duration jittered(Duration limit) {
        long span = limit.toMillis() * SessionTimeouts.LIFETIME_JITTER_PERCENT / 100;
        if (span <= 0) {
            return limit;
        }
        return limit.minusMillis(java.util.concurrent.ThreadLocalRandom.current().nextLong(span + 1));
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
        // 커맨드 생성(신뢰경계 검증)은 반드시 flatMap 안에서 한다 — 밖에서 만들면 인자 평가 위치에서 터져
        // 아래 onErrorResume을 지나치고, 인바운드 스트림이 죽어 연결째 끊긴다. flatMap 안의 throw는
        // onError 신호가 되므로 그 프레임만 ERROR로 돌아간다.
        // 레이트리밋 창 안이면 Redis에 다시 묻지 않는다 — 답을 이미 안다. 묻는 비용이 왕복 3회(강퇴 조회·
        // SET NX·잔여 TTL)라, 회선 속도로 미는 클라 하나가 그대로 Redis 부하가 된다. 창이 끝나면 평소대로 묻는다.
        long retryAfter = registry.rateLimitRetryAfterSeconds(sid);
        if (retryAfter > 0) {
            return respondRejected(sid, OutboundMessage.rateLimit(retryAfter, clientMsgId));
        }
        // 방이 아직 살아있는지 게이트에 맡긴다 — 간격 안이면 Redis에 가지 않고 곧장 true다.
        return entryGate.isRoomAlive(sid, chatSession)
                .flatMap(alive -> sendUseCase.send(buildCommand(chatSession, in, alive)))
                .flatMap(view -> registry.sendToSession(sid, toAck(view, clientMsgId)))
                .onErrorResume(e -> {
                    if (e instanceof ChatRateLimitException rle) {
                        registry.recordRateLimited(sid, rle.getRetryAfterSeconds());
                    }
                    // 밴도 같은 처리를 받는다 — 사유를 실어 보내고 반드시 닫는다. 밴은 계정 전체라
                    // 이 세션이 살아 있을 이유가 강퇴보다 더 없고, 안 닫으면 프레임마다 밴 조회를 태운다.
                    if (e instanceof UserKickedException || e instanceof UserBannedException) {
                        // 여기까지 왔다 = 이 Pod가 강퇴 신호를 못 받았다(받았으면 이미 닫혀 위에서 걸린다).
                        // 방금 권위 있게 확인했으니 닫는다 — 안 닫으면 계속 읽으면서 프레임마다 강퇴 조회를 태운다.
                        //
                        // 순서가 중요하다. 종료를 먼저 걸면 sink가 닫혀 뒤이은 사유 프레임이 FAIL_TERMINATED로
                        // 조용히 사라진다 — 클라는 1008만 받고 무엇 때문인지, 어느 메시지가 실패했는지 모른다.
                        // 그래서 사유를 먼저 실어 보내고, 성공하든 실패하든 반드시 닫는다(doFinally).
                        //
                        // 솎기도 거치지 않는다. 솎기는 되돌림이 반복될 때 비용이 되기 때문인데, 강퇴는 그 자리에서
                        // 세션을 끝내므로 연결당 한 번뿐이다. 반복이 없으니 증폭도 없고, 우회로가 되지도 않는다.
                        // 취소는 제외한다 — 그때는 이미 세션이 끝나는 중이고, 여기서 또 닫으면 아직
                        // 사유가 정해지지 않은 정상 종료를 "강퇴로 끊김"으로 덮어쓴다(경고 로그도 함께 남는다).
                        return registry.sendToSession(sid, toError(e, clientMsgId))
                                .doFinally(signal -> {
                                    if (signal != SignalType.CANCEL) {
                                        registry.terminateKicked(sid);
                                    }
                                });
                    }
                    if (e instanceof LiveNotActiveException) {
                        // 이 Pod가 종료 신호를 놓쳤고, 게이트가 마커를 읽어 방금 그 사실을 확인했다.
                        // 와이어에 나가는 프레임과 close code(1000)를 정상 경로와 똑같이 맞춘다 —
                        // 프론트가 "뒤늦게 안 종료"를 위한 분기를 따로 만들 필요가 없어야 한다.
                        //
                        // 다만 닫는 범위는 다르다. 정상 경로는 방 전체(renderToRoom+closeAll)를 닫지만
                        // 여기는 이 세션 하나뿐이다 — 마커를 읽는 계기가 "이 세션이 보냈다"라서다.
                        // 같은 Pod의 수신 전용 세션은 각자 보낼 때까지 닫히지 않는다. 방 단위로 닫으려면
                        // 종료 처리 5단계를 transport에서 복제해야 하고, 그러면 한쪽만 갱신되는 분기가 생긴다.
                        //
                        // ERROR를 먼저 보내는 건 발신자의 낙관적 말풍선을 되돌릴 키(clientMsgId)를
                        // 나르는 프레임이 그것뿐이라서다. SYSTEM에는 clientMsgId가 실리지 않는다.
                        // 강퇴와 같은 이유로 솎기도 거치지 않는다 — 세션이 그 자리에서 끝나 반복이 없다.
                        return registry.sendToSession(sid, toError(e, clientMsgId))
                                .then(systemMessageService.renderToSession(sid, SystemMessageCode.ROOM_ENDED))
                                .doFinally(signal -> {
                                    if (signal != SignalType.CANCEL) {
                                        registry.terminateRoomEnded(sid);
                                    }
                                });
                    }
                    return respondRejected(sid, toError(e, clientMsgId));
                });
    }

    /**
     * 파싱 불가 프레임에 ERROR로 답한다. clientMsgId는 알 수 없어 싣지 못한다.
     */
    private Mono<Void> rejectMalformed(String sid) {
        return respondRejected(sid, OutboundMessage.error("VALIDATION", null));
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
     * 예외는 강퇴와 방 종료 확인 둘이다 — 그 자리에서 세션이 끝나 반복될 수 없으니 증폭이 없다.
     */
    private Mono<Void> respondRejected(String sid, OutboundMessage response) {
        return registry.shouldReplyToRejection(sid)
                ? registry.sendToSession(sid, response)
                : Mono.empty();
    }

    /**
     * 한 세션의 인바운드 프레임을 <b>하나씩 차례로</b> 처리한다. (테스트 진입점)
     *
     * <p>concatMap이어야 한다. flatMap은 기본 동시성이 256이라, 클라가 프레임을 파이프라이닝하면
     * 최대 256건이 응답을 기다리지 않고 한꺼번에 안으로 들어간다. 그러면 레이트리밋 로컬 창이 무력해진다 —
     * 창은 첫 거부가 돌아온 뒤에야 무장되는데 그 시점엔 이미 전부 통과해 Redis를 다녀온 뒤다.
     *
     * <p>병렬로 처리해서 얻는 것도 없다. 오히려 저장·중계 순서가 전송 순서와 어긋난다
     * (레이트리밋이 면제되는 방 주인에게서 실제로 드러난다).
     */
    Mono<Void> runInbound(Flux<String> payloads, String sid, ChatSession chatSession) {
        return payloads.concatMap(payload -> onInbound(sid, chatSession, payload)).then();
    }

    // ── 순수 변환 (단위 테스트 진입점) ──

    /**
     * 인바운드 프레임을 전송 커맨드로 옮긴다.
     *
     * <p>{@code isRoomAlive}는 게이트가 준 값을 그대로 싣는다. 평상시엔 세션이 살아있다는 것 자체가 근거지만
     * (입장에서 마커를 보고, 접속 중 종료는 세션을 닫는다) 종료 신호가 무영속 Pub/Sub이라 그 순간 구독이
     * 끊겼던 Pod는 통째로 놓친다. 그 Pod의 세션은 닫히지 않으므로 게이트가 간격을 두고 다시 확인한다.
     * 유실 자체를 없애는 건 종료 이벤트를 영속 전달로 바꾸는 쪽(live 소유)이다.
     */
    SendChatCommand buildCommand(ChatSession s, InboundMessage in, boolean isRoomAlive) {
        return new SendChatCommand(
                s.roomId(), s.userId(), s.role().name(), s.isRoomOwner(),
                isRoomAlive,
                s.nickname(), s.email(),
                in.type(), in.content(), in.clientMsgId());
    }

    OutboundMessage toAck(ChatMessageView view, String clientMsgId) {
        return OutboundMessage.ack(view.id(), view.createdAt(), clientMsgId);
    }

    /** 거부 응답에 되돌려 실을 만큼만 남긴다. 계약 상한(64자)을 넘는 값은 어차피 거부된다. */
    private String truncateForEcho(String clientMsgId) {
        int max = ChatConstants.MAX_CLIENT_MSG_ID_LENGTH;
        return clientMsgId == null || clientMsgId.length() <= max ? clientMsgId : clientMsgId.substring(0, max);
    }

    OutboundMessage toError(Throwable e, String clientMsgId) {
        if (e instanceof ChatRateLimitException rle) {
            return OutboundMessage.rateLimit(rle.getRetryAfterSeconds(), clientMsgId);
        }
        return OutboundMessage.error(errorCode(e), clientMsgId);
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
        // 밴을 KICKED로 뭉뚱그리지 않는다 — 강퇴는 이 방 하나이고 밴은 계정 전체라, 클라이언트가
        // "다른 방으로 가면 된다"를 안내해도 되는지가 갈린다.
        if (e instanceof UserBannedException) {
            return "BANNED";
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
        return OutboundMessage.roomInfo(activeCount, isRoomOwner);
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
        OutboundMessage system = OutboundMessage.system(systemCode(reason));
        // 사유가 무엇이든 1008이다. 접속 중 방이 끝난 경우(terminateRoomEnded)는 1000인데 여기만 1008인 게
        // 비대칭으로 보이지만, 그쪽은 "성립해 있던 세션의 정상 종료"고 이쪽은 "세션이 성립하지 못한 거부"다.
        // 프론트 계약도 1008을 강퇴·밴과 함께 "입장 거부"로 묶어두고 있고, 무엇 때문인지는 함께 보낸
        // SYSTEM code가 전달한다. 재접속 정책도 두 코드가 같아(둘 다 하지 않음) 실제 동작 차이가 없다.
        return session.send(Mono.just(session.textMessage(serialize(system))))
                .then(session.close(CloseStatus.POLICY_VIOLATION));
    }

    /** 거부 사유를 클라가 렌더할 code로. switch 식이라 Reason이 늘면 컴파일이 막는다(조용한 오매핑 방지). */
    private SystemMessageCode systemCode(EntryDeniedException.Reason reason) {
        return switch (reason) {
            case KICKED -> SystemMessageCode.KICKED;
            case BANNED -> SystemMessageCode.BANNED;
            case ROOM_ENDED -> SystemMessageCode.ROOM_ENDED;
        };
    }

    String serialize(OutboundMessage message) {   // 테스트가 와이어 직렬화까지 태울 수 있게 package-private
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            throw new IllegalStateException("OutboundMessage 직렬화 실패", e);   // 평탄 record라 실제로는 발생 안 함
        }
    }
}
