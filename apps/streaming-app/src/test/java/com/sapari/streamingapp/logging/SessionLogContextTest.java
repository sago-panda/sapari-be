package com.sapari.streamingapp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.domain.repository.ChatBanRepository;
import com.sapari.chat.domain.repository.ChatKickRepository;
import com.sapari.chat.domain.repository.ChatRoomEndedRepository;
import com.sapari.global.logging.LogFields;
import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.streamingapp.websocket.ChatSessionRegistry;
import com.sapari.streamingapp.websocket.ChatWebSocketHandler;
import com.sapari.streamingapp.websocket.EntryGate;
import com.sapari.streamingapp.websocket.NettyConnectionRegistry;
import com.sapari.streamingapp.websocket.SessionTimeouts;
import com.sapari.streamingapp.websocket.auth.RoomTokenVerifier;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketSession;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/**
 * 열화 로그가 <b>어느 세션·어느 방</b>인지 들고 나가는지 본다 — 로그 이벤트의 MDC 를 직접 읽는다.
 *
 * <p>왜 이 자리인가: 입장 게이트의 fail-open 로그는 {@code onErrorResume} 람다 안에서 찍히고, 실측으로
 * 그 안에서 MDC 는 비어 있었다. 그래서 "게이트가 열렸다" 는 줄이 <b>어느 방에서 열렸는지 없이</b>
 * 쌓이고 있었다. 로그 문장이 roomId 를 직접 싣고 있어 사람이 읽을 수는 있었지만, 필드가 아니라
 * 메시지 안 문자열이라 "그 방의 모든 줄" 로 묶는 쿼리가 불가능했다.
 *
 * <p>단언 대상을 출력 문자열이 아니라 {@code ILoggingEvent} 의 MDC 맵으로 둔다 — 그게 ECS 가 JSON
 * 필드로 올리는 바로 그 값이고, 포맷을 바꿔도 이 테스트는 계속 같은 것을 지킨다.
 */
@DisplayName("세션 로그 컨텍스트 — 열화 로그가 session_id·room_id 를 들고 나간다")
class SessionLogContextTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);

    private final UUID roomId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private Logger gateLogger;
    private ListAppender<ILoggingEvent> appender;
    private EntryGate gate;

    @BeforeAll
    static void wire() {
        new ReactiveLogContextConfig().reactiveLogContext();
    }

    @BeforeEach
    void setUp() {
        ChatRoomEndedRepository roomEnded = mock(ChatRoomEndedRepository.class);
        ChatKickRepository kicks = mock(ChatKickRepository.class);
        ChatBanRepository bans = mock(ChatBanRepository.class);
        // 게이트 조회가 실패한다 → fail-open 으로 통과시키고 그 사실을 로그로 남긴다
        given(roomEnded.isEnded(any())).willReturn(
                Mono.<Boolean>error(new IllegalStateException("Redis down"))
                        .subscribeOn(Schedulers.boundedElastic()));
        given(kicks.isKicked(any(), any())).willReturn(Mono.just(false));
        given(bans.isBanned(any())).willReturn(Mono.just(false));
        gate = new EntryGate(kicks, roomEnded, bans, userId -> Mono.just(false), ChatMetrics.NOOP,
                mock(ChatSessionRegistry.class));

        gateLogger = (Logger) LoggerFactory.getLogger(EntryGate.class);
        appender = new MdcSafeAppender();
        appender.start();
        gateLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        gateLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("⭐ fail-open 로그에 session_id·room_id 가 붙는다 — MDC 만으로는 여기가 비어 있었다")
    void theFailOpenLineCarriesTheSessionContext() {
        // given
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);

        // when: 핸들러가 싣는 것과 같은 컨텍스트로 구동한다
        gate.verify(session)
                .contextWrite(Context.of(
                        LogFields.SESSION_ID, "s-42",
                        LogFields.ROOM_ID, roomId.toString()))
                .block(VERIFY_CEILING);

        // then
        assertThat(appender.list).as("fail-open 로그가 남지 않았다 — 흡수가 침묵이 됐다").hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .as("열화 로그가 어느 세션·어느 방인지 없이 나갔다")
                .containsEntry(LogFields.SESSION_ID, "s-42")
                .containsEntry(LogFields.ROOM_ID, roomId.toString());
    }

    /**
     * ⭐ <b>핸들러의 배선</b>을 통째로 구동한다.
     *
     * <p>위 두 테스트는 컨텍스트를 테스트가 직접 실어서 기계장치만 본다. 그걸로는 못 잡는 실패가
     * 있다 — {@code handle()} 에서 {@code contextWrite} 가 빠지면 기계장치는 멀쩡하고 <b>실제
     * 세션의 로그만 식별자를 잃는다</b>. 실측으로 확인했다: 그 한 줄을 지워도 다른 어떤 테스트도
     * 깨지지 않았다.
     *
     * <p>대상 로그는 {@code runSession} 안의 시청자 수 조회 fail-open 이다 — 그 자리라야
     * {@code session_id}·{@code room_id} 와 <b>안쪽에 따로 실은 {@code user_id} 까지</b> 한 번에 재진다.
     */
    @Test
    @DisplayName("⭐ handle() 배선 — 실제 세션의 fail-open 로그가 세 식별자를 다 들고 나간다")
    void theHandlerWiresTheContextForTheWholeSession() {
        // given: 핸드셰이크 모양을 갖춘 세션
        WebSocketSession socket = mock(WebSocketSession.class);
        given(socket.getId()).willReturn("s-99");
        given(socket.getHandshakeInfo()).willReturn(new HandshakeInfo(
                URI.create("wss://x/ws/chat?roomId=" + roomId),
                headersWithToken(), Mono.empty(), null));
        given(socket.receive()).willReturn(Flux.never());
        given(socket.send(any())).willReturn(Mono.never());
        given(socket.close(any())).willReturn(Mono.empty());

        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);
        RoomTokenVerifier verifier = mock(RoomTokenVerifier.class);
        given(verifier.verify(anyString(), any())).willReturn(Mono.just(chatSession));

        EntryGate openGate = mock(EntryGate.class);
        given(openGate.verify(any())).willReturn(Mono.empty());
        given(openGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(true));

        ChatSessionRegistry registry = mock(ChatSessionRegistry.class);
        given(registry.register(anyString(), any())).willReturn(Mono.empty());
        // ⭐ 이 조회가 실패하면 수 없이 입장을 진행하고 그 사실을 로그로 남긴다 — 그 줄을 본다
        given(registry.getActiveCount(roomId))
                .willReturn(Mono.<Long>error(new IllegalStateException("Redis down"))
                        .subscribeOn(Schedulers.boundedElastic()));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames(anyString())).willReturn(Flux.never());
        given(registry.terminationSignal(anyString())).willReturn(Mono.never());
        given(registry.closeStatusOf(anyString())).willReturn(CloseStatus.NORMAL);

        SystemMessageService systemMessages = mock(SystemMessageService.class);
        given(systemMessages.renderToSession(anyString(), any())).willReturn(Mono.empty());

        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                verifier, openGate, registry, mock(SendChatUseCase.class),
                mock(ChatBroadcastSubscriber.class), mock(NettyConnectionRegistry.class),
                systemMessages, new SessionTimeouts(Duration.ofHours(1), Duration.ofSeconds(30),
                        Duration.ofSeconds(90)),
                ChatMetrics.NOOP);

        Logger handlerLogger = (Logger) LoggerFactory.getLogger(ChatWebSocketHandler.class);
        ListAppender<ILoggingEvent> handlerAppender = new MdcSafeAppender();
        handlerAppender.start();
        handlerLogger.addAppender(handlerAppender);

        // when
        try {
            StepVerifier.create(handler.handle(socket)).thenAwait(Duration.ofMillis(300))
                    .thenCancel().verify(VERIFY_CEILING);

            // then
            assertThat(handlerAppender.list)
                    .as("시청자 수 조회 fail-open 로그가 남지 않았다")
                    .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("시청자 수 조회 실패"));
            ILoggingEvent failOpen = handlerAppender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("시청자 수 조회 실패"))
                    .findFirst().orElseThrow();
            assertThat(failOpen.getMDCPropertyMap())
                    .as("handle() 이 컨텍스트를 싣지 않아 실제 세션 로그가 식별자를 잃었다")
                    .containsEntry(LogFields.SESSION_ID, "s-99")
                    .containsEntry(LogFields.ROOM_ID, roomId.toString())
                    .containsEntry(LogFields.USER_ID, userId.toString());
        } finally {
            handlerLogger.detachAppender(handlerAppender);
            handlerAppender.stop();
        }
    }

    /** 룸 토큰은 Sec-WebSocket-Protocol 헤더로 온다 — 핸들러가 거기서 읽는다. */
    private static HttpHeaders headersWithToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Sec-WebSocket-Protocol", "bearer, token-value");
        return headers;
    }

    @Test
    @DisplayName("컨텍스트를 싣지 않으면 비어 있다 — 위 테스트가 컨텍스트 덕분임을 보인다")
    void withoutTheContextTheLineHasNoIdentifiers() {
        // given
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "닉", "e@example.com", false);

        // when
        gate.verify(session).block(VERIFY_CEILING);

        // then
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .doesNotContainKey(LogFields.SESSION_ID)
                .doesNotContainKey(LogFields.ROOM_ID);
    }

    /**
     * 로그를 <b>로깅 스레드에서 확정</b>하고 스레드 안전한 목록에 쌓는 수집기.
     *
     * <p><b>왜 {@code ListAppender} 를 그대로 못 쓰는가.</b> {@code LoggingEvent.getMDCPropertyMap()} 은
     * 지연 캡처다 — 필드가 비어 있으면 <b>부른 스레드</b>의 MDC 를 읽어 그 값을 박아 버린다. 이 파일이
     * 보는 로그는 전부 {@code boundedElastic} 에서 찍히고 단언은 메인 스레드가 읽으므로, 메인이 먼저
     * 부르면 <b>메인의 빈 MDC</b> 가 박히고 가드는 "식별자가 없다" 며 실패한다.
     *
     * <p><b>실측</b>: 이 가드들은 루트 콘솔 어펜더가 뒤이어 돌면서
     * {@code OutputStreamAppender.subAppend} → {@code prepareForDeferredProcessing()} 으로 MDC 를
     * 대신 확정해 주는 덕에 통과하고 있었다 — 정답이 자기와 무관한 어펜더 하나에 매달려 있었다.
     * {@code append} 안에서 직접 부르면 그 의존이 끊긴다.
     *
     * <p>목록 교체는 두 번째 결함을 막는다. {@code thenCancel().verify()} 는 세션 안쪽에서 비동기로
     * 찍히는 fail-open 줄을 기다려 주지 않으므로 그 쓰기에는 happens-before 가 없다. 상위 클래스의
     * {@code ArrayList} 는 가시성 보장이 없고 순회 중 변경에 취약하다. {@code list} 는 {@code public}
     * 비-final 이라 교체가 가능하고, 상위 {@code append} 가 이 목록에 쌓는다.
     */
    private static final class MdcSafeAppender extends ListAppender<ILoggingEvent> {

        private MdcSafeAppender() {
            this.list = new CopyOnWriteArrayList<>();
        }

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
