package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;

import com.sapari.chat.application.port.ChatMetrics;
import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
import com.sapari.chat.domain.exception.ChatSendUnavailableException;
import com.sapari.chat.domain.exception.ClientMsgIdReusedException;
import com.sapari.chat.domain.exception.LiveNotActiveException;
import com.sapari.chat.domain.exception.UserKickedException;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;
import com.sapari.chat.port.SendChatUseCase;
import com.sapari.chat.view.ChatMessageView;
import com.sapari.streamingapp.websocket.auth.RoomTokenVerifier;

import org.mockito.InOrder;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelId;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatWebSocketHandlerTest {

    /** 반응형 종결자(.block() / StepVerifier) 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
    private static final Duration VERIFY_CEILING = Duration.ofSeconds(10);
    private ChatBroadcastSubscriber subscriber;
    private ChatSessionRegistry registry;
    private SendChatUseCase sendUseCase;
    private ChatWebSocketHandler handler;

    private NettyConnectionRegistry connections;
    private EntryGate entryGate;
    private SystemMessageService systemMessageService;

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        subscriber = mock(ChatBroadcastSubscriber.class);
        registry = mock(ChatSessionRegistry.class);
        sendUseCase = mock(SendChatUseCase.class);
        connections = mock(NettyConnectionRegistry.class);
        entryGate = mock(EntryGate.class);
        // 기본은 "방 살아있음" — 재확인 창이 닫혀 있을 때(대다수 프레임)의 동작이다.
        given(entryGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(true));
        systemMessageService = mock(SystemMessageService.class);
        given(systemMessageService.renderToSession(anyString(), any())).willReturn(Mono.empty());
        handler = handlerWithLifetime(Duration.ofHours(1));
    }

    /** 핸들러가 생존 확인에 넘겨야 하는 기한. 목이 이 값으로만 답하므로 다른 값을 넘기면 스텁이 빗나간다. */
    private static final Duration LIVENESS_DEADLINE = Duration.ofSeconds(90);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

    private ChatWebSocketHandler handlerWithLifetime(Duration lifetime) {
        return new ChatWebSocketHandler(
                mock(RoomTokenVerifier.class), entryGate,
                registry, sendUseCase, subscriber, connections, systemMessageService,
                new SessionTimeouts(lifetime, PING_INTERVAL, LIVENESS_DEADLINE), ChatMetrics.NOOP);
    }

    /**
     * ⭐ 조립 중 동기 예외가 나도 방 구독 refcount 가 남지 않는다.
     *
     * <p>{@code acquireRoom} 은 조립 첫 줄이고 해제는 {@code doFinally} 의 {@code cleanup} 이 한다.
     * 그 둘 사이에서 동기 예외가 나면 <b>doFinally 가 붙은 Mono 가 애초에 반환되지 않아</b> 방
     * 구독이 영구히 남는다 — 그 방에 아무도 없는데 이 파드가 계속 그 방의 모든 메시지를 받는다.
     *
     * <p>{@code Mono.defer} 로 감싸는 것만으로는 안 닫힌다(실측: 그 모양도 {@code cleanup=0}). 닫히는
     * 것은 {@code doFinally} 가 defer <b>밖</b>에 있을 때뿐이다. 이 테스트가 그 배치를 고정한다 —
     * 안으로 옮기면 빨개진다.
     */
    @Test
    @DisplayName("⭐ 조립 중 던져도 방 구독이 남지 않는다 — 남으면 빈 방을 영구히 중계한다")
    void a_synchronous_throw_during_assembly_releases_the_room() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        // 조립 중 동기 예외 — register 의 즉시 실행 구간이 던지는 모양이다.
        given(registry.register(eq("s1"), any()))
                .willThrow(new IllegalStateException("프로브: 조립 중 던진다"));
        // 뒷정리가 정상으로 돌게 둔다 — 이 테스트가 재는 것은 doFinally 의 <b>배치</b>다.
        // (뒷정리 자신이 던지는 경우는 아래 테스트가 따로 본다.)
        given(registry.unregister(roomId, "s1")).willReturn(Mono.empty());
        // 방 구독 목이 Disposable 을 돌려줘야 한다 — null 이면 releaseRoom 안에서 dispose() 가 NPE 를
        // 내고 항목이 남아, 이 테스트가 재려는 것과 무관한 이유로 빨개진다.
        given(subscriber.subscribeRoom(roomId)).willReturn(() -> { });
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        StepVerifier.create(handler.runSession(session, chatSession))
                .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class))
                .verify(VERIFY_CEILING);

        assertThat(handler.isSubscribed(roomId))
                .as("조립 중 던졌는데 방 구독이 남았다 — 빈 방을 영구히 중계한다").isFalse();
    }

    /**
     * ⭐ 방을 못 얻었으면 해제하지 않는다 — 그러지 않으면 <b>남의 구독을 끊는다.</b>
     *
     * <p>{@code acquireRoom} 자신이 던지면 defer 가 그것을 {@code onError} 로 바꾸므로 얻지 않은
     * 방에 대해 {@code doFinally} 가 돈다(실측: {@code acquired=0 released=1}). 그 사이 다른 세션이
     * 같은 방을 {@code refs=1} 로 설치했으면 이 해제가 그걸 0 으로 내려 dispose 한다 — 살아 있는
     * 세션이 크로스 파드 강퇴·밴·방 종료를 조용히 못 받게 된다.
     *
     * <p>교차를 실제로 만든다 — 뒷정리는 {@code unregister} 를 먼저 부르고 그 다음에 방을 해제하므로,
     * 그 {@code unregister} 안에서 다른 세션의 획득을 끼워 넣으면 "던짐 → 설치 → 해제" 순서가 된다.
     * 처음에는 그 교차 없이 "{@code dispose} 가 안 불린다" 만 단언했는데, 그러면 해제가 빈 맵에
     * 떨어져 어차피 무연산이라 <b>플래그를 지워도 초록이었다</b>(실측).
     */
    @Test
    @DisplayName("⭐ 방을 못 얻었으면 해제하지 않는다 — 해제하면 남의 구독을 끊는다")
    void a_failed_acquire_does_not_release_the_room() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        Disposable disposable = mock(Disposable.class);
        // 구독 자체가 실패한다 — 그러면 roomSubs 에 매핑이 설치되지 않는다. 두 번째 호출은 성공해
        // "다른 세션" 의 설치가 된다.
        given(subscriber.subscribeRoom(roomId))
                .willThrow(new IllegalStateException("프로브: subscribeRoom 이 던진다"))
                .willReturn(disposable);
        // 던짐과 해제 <b>사이</b>에 다른 세션이 같은 방을 refs=1 로 설치한다. 뒷정리가 unregister 를
        // 먼저 부르므로 그 안이 정확히 그 틈이다.
        given(registry.unregister(roomId, "s1")).willAnswer(invocation -> {
            handler.acquireRoom(roomId);
            return Mono.empty();
        });
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        StepVerifier.create(handler.runSession(session, chatSession))
                .expectError(IllegalStateException.class)
                .verify(VERIFY_CEILING);

        assertThat(handler.isSubscribed(roomId))
                .as("실패한 접속의 뒷정리가 다른 세션의 방 구독을 걷어냈다").isTrue();
        then(disposable).should(never()).dispose();

        // ⚠️ 이 교차는 뒷정리의 <b>내부 순서</b>(unregister 먼저 → 방 해제 나중)에 기댄다. 순서를
        // 뒤집으면 설치가 해제 뒤로 가서 이 테스트가 과다 해제를 <b>조용히 못 잡는다</b>(실측: 순서
        // 뒤집기 + 플래그 무시 → 전 스위트 초록). 운영의 교차는 시간 기반이라 순서를 바꿔도 결함이
        // 남으므로, 순서에 기대지 않는 걸쇠를 아래 테스트가 따로 건다 —
        // {@code a_failed_acquire_never_calls_release}.
        //
        // 순서를 InOrder 로 못 박으려 했는데 그것도 공단언이었다: subscribeRoom 이 두 번 불려
        // (A 의 실패 + B 의 설치) 역순에서도 "unregister 다음 subscribeRoom" 이 성립한다.
    }

    /**
     * ⭐ 방을 못 얻었으면 {@code releaseRoom} 을 <b>부르지 않는다</b> — 순서와 무관한 걸쇠.
     *
     * <p>위 교차 테스트는 뒷정리의 내부 순서에 기대므로 그 순서를 바꾸면 조용히 효력을 잃는다. 이
     * 테스트는 그 대신 불변식을 직접 잰다 — 획득이 실패했으면 해제 호출이 <b>0회</b>여야 한다. 핸들러
     * 자신을 spy 로 감싸는 이유가 그것이다: 해제는 같은 객체의 메서드라 목으로는 안 보인다.
     */
    @Test
    @DisplayName("⭐ 방을 못 얻으면 해제를 아예 부르지 않는다 — 뒷정리 순서와 무관한 걸쇠")
    void a_failed_acquire_never_calls_release() {
        ChatWebSocketHandler spied = spy(handlerWithLifetime(Duration.ofHours(1)));
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.unregister(roomId, "s1")).willReturn(Mono.empty());
        given(subscriber.subscribeRoom(roomId))
                .willThrow(new IllegalStateException("프로브: subscribeRoom 이 던진다"));
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        StepVerifier.create(spied.runSession(session, chatSession))
                .expectError(IllegalStateException.class)
                .verify(VERIFY_CEILING);

        then(spied).should(never()).releaseRoom(roomId);
    }

    /**
     * ⭐ {@code dispose()} 가 던져도 <b>맵 상태가 일관된다</b>, 그리고 그 던짐이 한 줄로 남는다.
     *
     * <p>두 가지를 한 번에 재는 이유는 둘이 같은 전제를 공유하기 때문이다 — 뒷정리가 그 던짐을 잡는
     * catch 를 두어 "{@code dispose} 는 던질 수 있다" 를 전제로 채택했으므로, 그 전제 아래 남는
     * 맵 상태도 복구돼야 한다. 한때 {@code dispose()} 를 재매핑 함수 안에서 불러 던지면
     * <b>{@code refs=0} 항목이 남았고</b>, 그러면 다음 세션이 구독 없이 그 항목만 올려 그 방의
     * 크로스 파드 채널이 죽은 채로 영구히 간다.
     */
    @Test
    @DisplayName("⭐ dispose 가 던져도 방 항목은 걷힌다 — 남으면 다음 세션이 구독 없이 그 방을 쓴다")
    void a_throwing_dispose_still_removes_the_room_entry() {
        Disposable throwing = mock(Disposable.class);
        org.mockito.BDDMockito.willThrow(new IllegalStateException("프로브: dispose 가 던진다"))
                .given(throwing).dispose();
        given(subscriber.subscribeRoom(roomId)).willReturn(throwing);

        handler.acquireRoom(roomId);
        // 던짐은 호출부로 올라간다(그 catch 는 cleanup 에 있고 이건 직접 호출이다). 재는 것은
        // 그 던짐 뒤에 <b>맵 상태가 일관된가</b>다.
        assertThatThrownBy(() -> handler.releaseRoom(roomId))
                .isInstanceOf(IllegalStateException.class);

        assertThat(handler.isSubscribed(roomId))
                .as("dispose 가 던지자 refs=0 항목이 남았다 — 다음 세션이 구독 없이 그 방을 쓴다")
                .isFalse();
        // 그 뒤 새 세션은 구독을 <b>새로</b> 만들 수 있어야 한다.
        given(subscriber.subscribeRoom(roomId)).willReturn(mock(Disposable.class));
        handler.acquireRoom(roomId);
        assertThat(handler.isSubscribed(roomId)).isTrue();
        then(subscriber).should(times(2)).subscribeRoom(roomId);
    }

    @Test
    @DisplayName("⭐ 뒷정리가 던져도 방 구독은 해제된다 — 마지막 보상이 자기 안에서 순서에 기대면 안 된다")
    void a_throwing_cleanup_still_releases_the_room() {
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any()))
                .willThrow(new IllegalStateException("프로브: 조립 중 던진다"));
        // 뒷정리의 첫 줄이 던진다. 그 뒤 방 구독 해제가 건너뛰어지면 refcount 가 영구히 남는다.
        given(registry.unregister(roomId, "s1"))
                .willThrow(new IllegalStateException("프로브: 뒷정리가 던진다"));
        // 방 구독 목이 Disposable 을 돌려줘야 한다 — null 이면 releaseRoom 안에서 dispose() 가 NPE 를
        // 내고 항목이 남아, 이 테스트가 재려는 것과 무관한 이유로 빨개진다.
        given(subscriber.subscribeRoom(roomId)).willReturn(() -> { });
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        StepVerifier.create(handler.runSession(session, chatSession))
                .expectError(IllegalStateException.class)
                .verify(VERIFY_CEILING);

        assertThat(handler.isSubscribed(roomId))
                .as("뒷정리가 던지자 방 구독이 남았다 — 해제가 finally 에 없다").isFalse();
    }

    /**
     * 강제 종료 유예를 묶는다 — <b>전 스위트가 이 분기를 한 번도 밟지 않았다.</b>
     *
     * <p>이 파일의 다른 칸 일곱과 {@code SessionLogContextTest} 가 모두
     * {@code registry.terminationSignal("s1")} 을 {@code Mono.never()} 로 스텁한다. 그래서 단위
     * 테스트에서 이 분기는 발화할 수 없었다. 통합 테스트에서는 제어 채널이 실제로 발화하지만
     * 데이터 채널이 수십 ms 로 이겨서 {@code firstWithSignal} 이 3초 전에 완료하고 이 경로를
     * 취소한다 — 그쪽 칸은 오히려 <b>이 분기가 쓰이지 않는다는 것을 단언한다</b>. 즉
     * {@code firstWithSignal} 에서 {@code forcedClose.then(closeWithReason)} 를 지우는 변이가
     * 전 스위트에서 생존했다.
     *
     * <p>커버리지보다 중요한 것: {@code ChatSessionRegistry.reapUnresponsiveIfFull} 의 javadoc 이
     * 이 상수에서 하중 있는 경계를 파생한다 — "(유저, 방)당 실제 세션 수가 최대 2배, 지속 ≤8초 …
     * {@code FORCED_CLOSE_GRACE} 3s + {@code CLOSE_FLUSH_TIMEOUT} 5s". 뒤쪽은 테스트 둘이 묶는데
     * 앞쪽은 하나도 없었다. <b>다른 파일이 인용하는 불변식의 절반이 안 묶인 상수에 얹혀 있었다.</b>
     *
     * <p>3초를 <b>리터럴</b>로 적는다. 상수는 {@code private} 이고, 더 중요하게는 그 수가 다른
     * 파일의 javadoc 에 인용돼 있어서 — 상수를 참조하면 값이 바뀔 때 단언이 따라가고 그 인용만
     * 조용히 거짓이 된다. 바꾸려면 이 줄과 그 javadoc 을 함께 보게 만드는 쪽이 맞다.
     */
    @Test
    @DisplayName("⭐ 강제 종료가 3초 유예 뒤에 닫는다 — 데이터 채널이 다 막힌 클라의 유일한 보상이다")
    void forced_close_fires_after_the_grace() {
        // given: outbound·inbound·send 가 전부 끝나지 않는 클라 — sink complete 가 버퍼에 막힌 모양
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        // ⚠️ close 를 반드시 스텁한다 — 안 하면 null 이 돌아와 .then(null) 로 NPE 가 나고,
        // 그 빨강은 "유예가 없다" 와 구분되지 않는다(SPR-178 A5 에서 실제로 밟았다).
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        // 이 칸의 하중: 제어 채널이 **실제로** 발화한다. 나머지 칸은 전부 Mono.never() 다.
        given(registry.terminationSignal("s1")).willReturn(Mono.just(CloseStatus.POLICY_VIOLATION));
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.POLICY_VIOLATION);
        ChatSession chatSession =
                new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when & then
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                // 유예 직전에는 아무것도 닫히지 않는다 — 여기서 닫히면 정상 클라가 남은 메시지를
                // 받기 전에 끊긴다(그게 유예의 존재 이유다)
                .thenAwait(Duration.ofSeconds(3).minusMillis(1))
                .then(() -> then(session).should(never()).close(any()))
                // 유예를 넘기면 사유를 실어 닫는다
                .thenAwait(Duration.ofMillis(2))
                // ⚠️ 상한을 준다. verifyComplete() 는 무상한이라, forcedClose 를 firstWithSignal 에서
                // 빼는 변이가 **빨강이 아니라 멈춤**이 된다 — 실측으로 300초를 넘겨 죽여야 했다.
                // 이 파일이 VERIFY_CEILING 을 두는 이유가 그것이다(근거: modules/chat/AGENTS.md).
                .expectComplete()
                .verify(VERIFY_CEILING);

        then(session).should(times(1)).close(CloseStatus.POLICY_VIOLATION);
    }

    /**
     * ⭐ 마지막으로 보낸 시청자 수를 입장 ROOM_INFO 를 <b>보낸 뒤</b> 지운다.
     *
     * <p>먼저 지우면 그 사이에 낀 회차가 기록을 남기고, 캐시를 거친 입장 수가 그 뒤에 도착해 화면에 남는다.
     * 호출 순서가 아니라 실행 순서가 계약이라 스텁이 실행될 때 이름을 적는다.
     */
    @Test
    @DisplayName("⭐ 입장 ROOM_INFO 를 보낸 뒤에 마지막 보낸 수를 지운다 — 먼저 지우면 낀 회차가 캐시 수를 고정한다")
    void the_last_pushed_count_is_forgotten_after_the_entry_room_info() {
        List<String> ran = new CopyOnWriteArrayList<>();
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.fromRunnable(() -> ran.add("entry")));
        org.mockito.BDDMockito.willAnswer(inv -> ran.add("forget")).given(registry).forgetLastPushedCount(roomId);
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.unregister(roomId, "s1")).willReturn(Mono.empty());
        given(subscriber.subscribeRoom(roomId)).willReturn(() -> { });
        ChatSession chatSession =
                new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        Disposable running = handler.runSession(session, chatSession).subscribe();
        try {
            assertThat(ran).containsExactly("entry", "forget");
        } finally {
            running.dispose();
        }
    }

    /**
     * ⭐ 죽은 자리 수거가 <b>배선돼 있는지</b> — 등록보다 먼저, 핸들러의 기한으로 불리는지.
     *
     * <p>수거 자체는 {@code SessionAdmissionCapTest} 가 네 건으로 고정한다. 그런데 그 보장은
     * <b>핸들러가 그것을 부를 때만</b> 값이 있고, 그 한 줄을 지워도 레지스트리 테스트는 전부 초록이다 —
     * {@code runSession} 자신의 javadoc 이 경고한 "분기만 따로 재면 배선이 빠진 것을 아무도 묻지
     * 않는다" 가 이 경우다.
     *
     * <p><b>순서가 하중이다.</b> 등록 뒤에 부르면 이미 거부된 뒤라 아무것도 구하지 못하고, 거부를
     * 받고 재시도하는 모양으로 바꾸면 거부 카운터가 집행 아닌 것을 집행으로 센다. 그래서
     * {@code InOrder} 로 "수거 → 등록" 을 고정한다.
     *
     * <p>기한을 {@code any()} 로 지우지 않는 이유는 위 생존 확인 테스트와 같다 — 핸들러가 수명 상한을
     * 기한으로 넘기는 뮤턴트가 통과하면, 60분 침묵에서만 자리가 비어 수거가 사실상 무효가 된다.
     */
    @Test
    @DisplayName("⭐ 죽은 자리 수거가 등록보다 먼저 배선돼 있다 — 안 부르면 상한이 정상 재접속을 막는다")
    void dead_slot_reaping_is_wired_before_register() {
        // given
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                .thenAwait(Duration.ofSeconds(1))
                .thenCancel()
                .verify(Duration.ofSeconds(10));

        // then
        InOrder order = inOrder(registry);
        // 기한 + 간격이다. 기한만 넘기면 생존 루프의 격자에서는 살아남는 구간(기한~기한+간격)에
        // 있던 멀쩡한 세션을 reap 이 죽이고 그 자리를 이 접속이 가져간다 — 그 값이 이 호출의
        // 하중이라 any() 로 지우지 않는다.
        order.verify(registry).reapUnresponsiveIfFull(
                userId, roomId, LIVENESS_DEADLINE.plus(PING_INTERVAL));
        order.verify(registry).register(eq("s1"), any());
    }

    /**
     * ⭐ 수명 상한이 <b>배선돼 있는지</b>를 {@code runSession}으로 직접 구동해 확인한다.
     *
     * <p>분기만 따로 재면 안 된다 — 그 분기를 {@code firstWithSignal}에서 빼도 아무 테스트가 깨지지
     * 않는다. 배선이 고정되는 자리는 이 메서드 하나다.
     *
     * <p>왜 필요한가: 입장 토큰은 핸드셰이크 때 한 번만 검증되고 그 안에 신원·역할·방 소유가 들어 있다.
     * 그 뒤의 역할 강등·방 소유권 이전·탈퇴 확정은 이 세션에 닿지 않는다(지금 전파되는 것은 밴 하나뿐).
     * 수명 상한이 그 전부를 유계로 만든다 — 재접속이 live의 입장을 다시 타므로 거기서 권위가 새로
     * 평가된다.
     */
    @Test
    @DisplayName("⭐ 기한 동안 아무 프레임도 올라오지 않으면 세션을 거둔다 — 끊긴 소켓이 수명 상한까지 남지 않게")
    void unresponsive_session_is_reclaimed() {
        // given: 소켓은 열려 있지만 아무것도 올라오지 않는다 — 끊긴 소켓과 구별되지 않는 상태다.
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        // 기한이 지났다고 레지스트리가 답한다 — 시계 자체는 레지스트리 단위 테스트가 지킨다.
        // 기한 인자를 any() 로 지우면 핸들러가 <b>수명 상한</b>을 기한으로 넘기는 뮤턴트가 통과한다 —
        // 그러면 생존 확인이 60분 침묵에서만 참이 되어 사실상 무효가 되는데 아무 테스트도 깨지지 않는다.
        given(registry.isUnresponsive("s1", LIVENESS_DEADLINE)).willReturn(true);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when & then: 수명 상한(1시간)보다 한참 먼저 거둬야 한다 — 그게 이 분기의 존재 이유다.
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                .thenAwait(Duration.ofMinutes(3))
                .then(() -> then(registry).should(times(1)).terminateUnresponsive("s1"))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    /**
     * ⭐ 첫 생존 점검이 <b>기한 + 간격</b> 전에는 돌지 않는다.
     *
     * <p>생존 시계가 등록 시각으로 초기화되므로, 기한에 바로 점검하면 첫 창에서만 유실 내성이 한 칸
     * 짧아진다 — 기본값에서 t=30s·60s 의 ping 이 둘 다 유실되면 t=90s 점검에서 {@code last} 가 아직
     * 등록 시각이라 {@code 90 - 0 >= 90} 이 되어 <b>멀쩡한 시청자가 끊긴다.</b> 정상 구간에서는 2회
     * 유실이 살고 3회가 끊기므로, "기한 = 3 × 간격 = 2회 내성" 이 첫 창에서만 거짓이었다.
     */
    @Test
    @DisplayName("⭐ 첫 생존 점검은 기한 + 간격 뒤다 — 첫 창에서 2회 유실에 멀쩡한 세션이 끊기지 않게")
    void the_first_liveness_check_waits_one_interval_past_the_deadline() {
        // given: 레지스트리가 "응답 없음" 이라고 계속 답한다 — 점검이 도는 순간 바로 거둔다
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        given(registry.isUnresponsive("s1", LIVENESS_DEADLINE)).willReturn(true);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when & then: 기한(90s)만으로는 아직 점검하지 않고, 간격 하나(30s)를 더 지나면 점검한다
        Duration firstCheck = LIVENESS_DEADLINE.plus(PING_INTERVAL);
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                .thenAwait(firstCheck.minusMillis(1))
                .then(() -> then(registry).should(never()).terminateUnresponsive(anyString()))
                .thenAwait(Duration.ofMillis(2))
                .then(() -> then(registry).should(times(1)).terminateUnresponsive("s1"))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    /**
     * ⭐ 생존 점검이 <b>간격마다</b> 다시 돈다 — 첫 점검 한 번으로 끝나지 않는다.
     *
     * <p>{@code Flux.interval(첫 점검, 간격)} 의 두 번째 인자가 주기다. 그걸 {@code maxLifetime} 으로
     * 바꿔도 첫 점검은 그대로라, 첫 틱에서 거두는 기존 테스트들은 전부 초록이다 — 그러면 죽은 소켓
     * 수거가 120초에서 <b>1시간</b>이 된다(첫 점검을 놓친 소켓은 수명까지 남는다).
     *
     * <p>그래서 첫 점검에서는 "아직 응답 있음" 으로 답하고 두 번째 틱에서 거두게 만든다.
     */
    @Test
    @DisplayName("⭐ 생존 점검은 간격마다 다시 돈다 — 첫 틱을 놓친 죽은 소켓이 수명까지 남지 않게")
    void the_liveness_check_repeats_every_interval() {
        // given: 첫 점검에서는 응답이 있고, 다음 점검에서 끊긴 것으로 확인된다
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        given(registry.isUnresponsive("s1", LIVENESS_DEADLINE)).willReturn(false, true);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when & then: 첫 점검 직후에는 아직 거두지 않고, 간격 하나를 더 지나면 거둔다.
        // 주기가 maxLifetime 으로 바뀌면 두 번째 틱이 1시간 뒤라 아래 단언이 깨진다.
        Duration firstCheck = LIVENESS_DEADLINE.plus(PING_INTERVAL);
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                .thenAwait(firstCheck.plusMillis(1))
                .then(() -> then(registry).should(never()).terminateUnresponsive(anyString()))
                .thenAwait(PING_INTERVAL)
                .then(() -> then(registry).should(times(1)).terminateUnresponsive("s1"))
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    /**
     * ⭐ 수요가 없을 때 버린 ping 을 <b>센다.</b>
     *
     * <p>한때 {@code onBackpressureDrop()} 을 인자 없이 써서, 이 줄만 이 파일의 규율에서 빠져 있었다 —
     * 세지도 로그되지도 않으니 줄을 통째로 지워도 스위트가 전부 초록이었다. 지금 드롭이 안 일어나는
     * 것은 설정 두 값의 비(ping 간격 × 32 = 16분 vs 생존 기한 90s)에 기대고 있어, 그 비가 바뀌면
     * 조용히 사라진다.
     *
     * <p>수요 0으로 직접 구독한다. {@code runSession} 을 거치면 {@code session.send} 가 수요를
     * 만들어 드롭이 재현되지 않는다.
     */
    @Test
    @DisplayName("⭐ 수요가 없어 버린 ping 을 센다 — 드롭이 숫자 없이 사라지지 않게")
    void dropped_pings_are_counted() {
        // given: 아웃바운드 수요가 없는 구독자
        WebSocketSession session = mock(WebSocketSession.class);

        // when & then: 간격 30s 로 2분 — 네 틱이 전부 버려진다
        StepVerifier.withVirtualTime(() -> handler.pings(session, Mono.never()), 0)
                .expectSubscription()
                .thenAwait(Duration.ofMinutes(2))
                .then(() -> assertThat(handler.pingsDropped())
                        .as("버린 ping 이 세어지지 않았다").isEqualTo(4))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    /**
     * ⭐ 버린 ping 이 <b>밖으로도</b> 나간다.
     *
     * <p>위 테스트는 프로세스 안의 카운터를 본다. 그 값은 로그로만 나가고 로그는 간격으로 솎아내므로,
     * 짧게 반복되는 드롭은 흔적이 없다. 이 값이 꾸준히 오르면 설정 두 값의 비가 깨졌다는 뜻이라
     * 알림을 걸 수 있어야 한다.
     */
    @Test
    @DisplayName("⭐ 버린 ping 이 stage=liveness_ping 으로 나간다 — 설정 비가 깨진 것을 알림으로 받으려면")
    void dropped_pings_are_reported_to_metrics() {
        // given: 실제 레지스트리를 든 핸들러 + 아웃바운드 수요가 없는 구독자
        io.micrometer.core.instrument.MeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ChatWebSocketHandler metered = new ChatWebSocketHandler(
                mock(RoomTokenVerifier.class), entryGate, registry, sendUseCase, subscriber, connections,
                systemMessageService, new SessionTimeouts(Duration.ofHours(1), PING_INTERVAL, LIVENESS_DEADLINE),
                new com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics(meters));
        WebSocketSession session = mock(WebSocketSession.class);

        // when & then
        StepVerifier.withVirtualTime(() -> metered.pings(session, Mono.never()), 0)
                .expectSubscription()
                .thenAwait(Duration.ofMinutes(2))
                .then(() -> assertThat(meters.get("chat.delivery.dropped")
                        .tags("stage", "liveness_ping").counter().count())
                        .as("버린 ping 이 지표로 나가지 않았다").isEqualTo(4))
                .thenCancel()
                .verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("⭐ 응답이 올라오는 동안은 거두지 않는다 — 말하지 않는 시청자가 대다수다")
    void responsive_session_is_not_reclaimed() {
        // given: 프레임은 올라오지만(= 소켓 살아 있음) 발화는 없다. 채팅의 정상 다수다.
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.isUnresponsive("s1", LIVENESS_DEADLINE)).willReturn(false);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when & then: 발화가 없다는 이유로 끊으면 정상 시청자가 전부 끊긴다
        StepVerifier.withVirtualTime(() -> handler.runSession(session, chatSession))
                .thenAwait(Duration.ofMinutes(30))
                .then(() -> then(registry).should(never()).terminateUnresponsive(anyString()))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("⭐ 수명 상한은 흩어지되 상한을 넘지 않는다 — 같이 들어온 세션이 같이 재접속하면 그게 부하다")
    void lifetime_is_jittered_below_the_limit() {
        // given: 상한이 통제 값이라 위로 넘기면 안 되고, 한 점에 몰리면 재접속이 폭주한다
        Duration limit = Duration.ofMinutes(60);

        // when: 여러 번 뽑는다
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            Duration jittered = handler.jittered(limit);
            seen.add(jittered.toMillis());
            assertThat(jittered).as("지터가 상한을 넘었다 — 통제 값이 느슨해진다").isLessThanOrEqualTo(limit);
            assertThat(jittered).as("지터 폭이 과하다 — 상한이 사실상 짧아진다")
                    .isGreaterThanOrEqualTo(limit.multipliedBy(80).dividedBy(100));
        }

        // then: 실제로 흩어진다 — 고정값을 돌려주면 지터가 없는 것과 같다
        assertThat(seen).as("지터가 흩어지지 않았다 — 같이 들어온 세션이 같은 순간에 끊긴다")
                .hasSizeGreaterThan(50);
    }

    /**
     * ⭐ 수명 분기가 <b>지터를 거쳐</b> 배선돼 있는지 본다.
     *
     * <p>{@code lifetime_is_jittered_below_the_limit} 는 헬퍼를 직접 부르고,
     * {@code session_is_terminated_at_the_lifetime_limit} 는 80% / 100%+1s 로 단언한다 —
     * 둘 다 지터 없이도 참이라, {@code Mono.delay(jittered(limit))} 를 {@code Mono.delay(limit)} 로
     * 되돌려도 아무것도 깨지지 않았다(실측: websocket 패키지 전체 BUILD SUCCESSFUL).
     * {@code runSession} 자신의 javadoc 이 경고한 "분기만 따로 재면 배선이 빠진 것을 아무도 묻지
     * 않는다" 가 지터에서 그대로 일어났다.
     *
     * <p>가상 시간이라 시계는 정확하다. 남는 불확실성은 난수 하나뿐이다 — 지터 폭이 60분의 15%
     * (540,000ms)이고 뽑기가 {@code [0, span]} 이라, 정확히 0이 나오면 상한에 발화해 이 테스트가
     * 깨진다. 확률 1/540,001 이고, 그 경우는 "지터가 없다"와 구별되지 않는 것이 맞다.
     */
    @Test
    @DisplayName("⭐ 수명 분기가 지터를 거쳐 배선돼 있다 — 상한 1ms 전에 이미 끊겨 있다")
    void lifetime_branch_is_wired_through_the_jitter() {
        // given: 클라도 서버도 먼저 끝내지 않는 세션 — 수명 분기만이 이 연결을 끝낼 수 있다
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        Duration lifetime = Duration.ofMinutes(60);
        ChatWebSocketHandler bounded = handlerWithLifetime(lifetime);

        // when & then: 지터를 거치지 않으면 정확히 상한에 발화하므로 1ms 전에는 아직 끊기지 않는다
        StepVerifier.withVirtualTime(() -> bounded.runSession(session, chatSession))
                .thenAwait(lifetime.minusMillis(1))
                .then(() -> then(registry).should(times(1)).terminateLifetimeExpired("s1"))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("⭐ 수명 상한에 닿으면 세션이 종료된다 — 재접속으로 권위를 다시 받게 한다")
    void session_is_terminated_at_the_lifetime_limit() {
        // given: 클라도 서버도 먼저 끝내지 않는 세션 — 수명 상한만이 이 연결을 끝낼 수 있다
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.getId()).willReturn("s1");
        given(session.receive()).willReturn(Flux.never());
        given(session.send(any())).willReturn(Mono.never());
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.register(eq("s1"), any())).willReturn(Mono.empty());
        given(registry.getActiveCount(roomId)).willReturn(Mono.just(1L));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.outboundFrames("s1")).willReturn(Flux.never());
        given(registry.terminationSignal("s1")).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);
        ChatSession chatSession = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        Duration lifetime = Duration.ofMinutes(60);
        ChatWebSocketHandler bounded = handlerWithLifetime(lifetime);

        // when & then: 지터 폭(상한의 15% 아래로) 바깥에서는 아직 끊지 않고, 상한을 지나면 끊는다.
        // 지터가 있으므로 "상한 직전"으로는 단언할 수 없다 — 최대 15% 일찍 발화한다.
        // verify 에 상한을 준다 — 기본값은 무한 대기라, 분기가 빠지면 이 테스트가 실패하는 대신 멈춘다.
        Duration beforeAnyJitter = lifetime.multipliedBy(80).dividedBy(100);
        StepVerifier.withVirtualTime(() -> bounded.runSession(session, chatSession))
                .thenAwait(beforeAnyJitter)
                .then(() -> then(registry).should(never()).terminateLifetimeExpired(anyString()))
                .thenAwait(lifetime.minus(beforeAnyJitter).plusSeconds(1))
                .then(() -> then(registry).should(times(1)).terminateLifetimeExpired("s1"))
                .thenCancel()
                .verify(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("buildCommand — 서버신뢰값은 세션에서, 클라값은 InboundMessage에서")
    void build_command_trusts_session_for_server_fields() {
        // given
        ChatSession session = new ChatSession(roomId, userId, ChatRole.SELLER, "셀러", "s@example.com", true);
        InboundMessage in = new InboundMessage("NOTICE", "공지입니다", "cmid-1");

        SendChatCommand c = handler.buildCommand(session, in, true);

        // when & then
        assertThat(c.roomId()).isEqualTo(roomId);
        assertThat(c.senderId()).isEqualTo(userId);
        assertThat(c.senderRole()).isEqualTo("SELLER");
        assertThat(c.isRoomOwner()).isTrue();
        assertThat(c.isRoomAlive()).isTrue();
        assertThat(c.senderNickname()).isEqualTo("셀러");
        assertThat(c.senderEmail()).isEqualTo("s@example.com");
        assertThat(c.messageType()).isEqualTo("NOTICE");   // 클라값
        assertThat(c.content()).isEqualTo("공지입니다");      // 클라값
        assertThat(c.clientMsgId()).isEqualTo("cmid-1");    // 클라값
    }

    @Test
    @DisplayName("toAck — type=ACK + serverId + clientMsgId + createdAt")
    void to_ack() {
        // given
        ChatMessageView view = new ChatMessageView(
                "65a1f2c3d4e5f60718293a4b", roomId, userId, "닉", null, "BUYER", "NORMAL",
                "hi", null, "cmid-9", Instant.parse("2026-06-11T00:00:00Z"));

        OutboundMessage ack = handler.toAck(view, "cmid-9");

        // when & then
        assertThat(ack.type()).isEqualTo("ACK");
        assertThat(ack.id()).isEqualTo("65a1f2c3d4e5f60718293a4b");
        assertThat(ack.clientMsgId()).isEqualTo("cmid-9");
        assertThat(ack.createdAt()).isEqualTo(Instant.parse("2026-06-11T00:00:00Z"));
    }

    @Test
    @DisplayName("toError — 레이트리밋은 RATE_LIMIT+retryAfter, 그 외는 ERROR+code, 모두 clientMsgId 운반")
    void to_error_maps_exceptions() {
        // given
        OutboundMessage rl = handler.toError(new ChatRateLimitException("x", 3), "c");

        // when & then
        assertThat(rl.type()).isEqualTo("RATE_LIMIT");
        assertThat(rl.retryAfterSeconds()).isEqualTo(3L);
        assertThat(rl.clientMsgId()).isEqualTo("c");

        assertThat(handler.toError(new LiveNotActiveException("x"), "c").code()).isEqualTo("NOT_ACTIVE");
        assertThat(handler.toError(new ChatPermissionDeniedException("x"), "c").code()).isEqualTo("PERMISSION");
        assertThat(handler.toError(new UserKickedException("x"), "c").code()).isEqualTo("KICKED");
        assertThat(handler.toError(new IllegalArgumentException("x"), "c").code()).isEqualTo("VALIDATION");
        assertThat(handler.toError(new ChatSendUnavailableException(new RuntimeException("x")), "c").code())
                .isEqualTo("UNAVAILABLE");
        assertThat(handler.toError(new ClientMsgIdReusedException(), "c").code()).isEqualTo("CLIENT_MSG_ID_REUSED");

        OutboundMessage internal = handler.toError(new RuntimeException("x"), "c");
        assertThat(internal.type()).isEqualTo("ERROR");
        assertThat(internal.code()).isEqualTo("INTERNAL");
        assertThat(internal.clientMsgId()).isEqualTo("c");
    }

    @Test
    @DisplayName("roomInfo — activeCount + isRoomOwner 운반(#44)")
    void room_info_carries_owner() {
        // given
        OutboundMessage ri = handler.roomInfo(5L, true);

        // when & then
        assertThat(ri.type()).isEqualTo("ROOM_INFO");
        assertThat(ri.activeCount()).isEqualTo(5L);
        assertThat(ri.isRoomOwner()).isTrue();
    }

    @Test
    @DisplayName("구독 ref-count — 같은 방 다중 입장은 구독 1개 공유, 마지막 퇴장에만 dispose")
    void room_subscription_ref_counted() {
        // given
        Disposable disposable = mock(Disposable.class);
        given(subscriber.subscribeRoom(roomId)).willReturn(disposable);

        handler.acquireRoom(roomId);
        handler.acquireRoom(roomId);

        // when & then
        then(subscriber).should(times(1)).subscribeRoom(roomId);   // 공유 — 구독은 1회만
        assertThat(handler.isSubscribed(roomId)).isTrue();

        handler.releaseRoom(roomId);
        then(disposable).should(never()).dispose();                // 아직 1명 남음
        assertThat(handler.isSubscribed(roomId)).isTrue();

        handler.releaseRoom(roomId);
        then(disposable).should(times(1)).dispose();                         // 마지막 퇴장 → 해제
        assertThat(handler.isSubscribed(roomId)).isFalse();
    }

    @ParameterizedTest(name = "payload={0}")
    @ValueSource(strings = { "null", "[]", "\"문자열\"", "123", "{}", "{\"content\":\"안녕\"}", "깨진json" })
    @DisplayName("onInbound — 어떤 페이로드가 와도 인바운드 스트림이 죽지 않는다(연결 유지)")
    void inbound_never_kills_stream(String payload) {
        // given: JSON 리터럴 null은 Jackson이 예외 없이 null을 반환해 catch를 그냥 통과한다
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willReturn(Mono.empty());
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when: 실제 수신 배선과 같은 flatMap 모양으로 태운다
        // then: 스트림이 정상 완료된다(에러로 끝나면 firstWithSignal이 종료되어 WS가 끊긴다)
        StepVerifier.create(Flux.just(payload)
                        .flatMap(p -> handler.onInbound("s1", session, p))
                        .then())
                .expectComplete().verify(VERIFY_CEILING);
    }

    @Test
    @DisplayName("입장 — 시청자 수를 못 읽어도 접속은 성립하고, 수만 비워 보낸다")
    void room_info_survives_active_count_failure() {
        // given: 시청자 수는 표시용이고 이 값의 출처(Redis HASH)에 메시지 전달·강퇴가 의존하지 않는다.
        // 강퇴 조회조차 실패 시 입장을 허용하는 정책(EntryGate)과 맞추려면 여기서 접속을 막으면 안 된다.
        given(registry.getActiveCount(roomId)).willReturn(Mono.error(new RuntimeException("redis blip")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.SELLER, "판매자", "s@example.com", true);

        // when
        OutboundMessage info = handler.roomInfoFor(roomId, session).block(VERIFY_CEILING);

        // then: 방주인 여부는 그대로 실리고(방주인 토글 UI), 수만 알 수 없음으로 비운다
        assertThat(info).isNotNull();
        assertThat(info.type()).isEqualTo("ROOM_INFO");
        assertThat(info.isRoomOwner()).isTrue();
        assertThat(info.activeCount()).isNull();
    }

    @Test
    @DisplayName("onInbound — 종료가 확정된 세션의 전송은 받지 않는다(유예 창 도배 차단)")
    void inbound_rejected_after_termination_decided() {
        // given: 강퇴/방종료로 종료가 확정된 세션. 소켓이 닫히기 전까지 짧은 창이 남는다.
        given(registry.isTerminating("s1")).willReturn(true);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(handler.onInbound("s1", session, "{\"type\":\"NORMAL\",\"content\":\"도배\"}"))
                .expectComplete().verify(VERIFY_CEILING);

        // then: 전송 파이프라인에 아예 들어가지 않는다 — Redis 강퇴 조회는 장애 시 통과(fail-open)라 믿을 수 없다
        then(sendUseCase).should(never()).send(any());
    }

    @Test
    @DisplayName("onInbound — type 누락이면 연결을 끊지 않고 ERROR(VALIDATION)만 응답한다")
    void inbound_without_type_keeps_stream_alive() {
        given(registry.shouldReplyToRejection(anyString())).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds(anyString())).willReturn(0L);
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when: 실제 수신 배선과 같은 flatMap 모양으로 태운다
        // (커맨드 생성이 인자평가 위치에서 throw하면 여기서 스트림이 죽는다)
        StepVerifier.create(Flux.just("{\"content\":\"안녕\"}")
                        .flatMap(payload -> handler.onInbound("s1", session, payload))
                        .then())
                .expectComplete().verify(VERIFY_CEILING);

        // then: 스트림은 살아있고 ERROR/VALIDATION만 나간다
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        then(registry).should(times(1)).sendToSession(eq("s1"), sent.capture());
        assertThat(sent.getValue().type()).isEqualTo("ERROR");
        assertThat(sent.getValue().code()).isEqualTo("VALIDATION");
    }

    @Test
    @DisplayName("종료 — close가 끝나지 않아도 상한 뒤 완료해 정리를 막지 않는다")
    void close_does_not_block_cleanup_forever() {
        // given: 소켓을 읽지 않는 클라 — close 프레임이 잔량 뒤에 줄 서서 write future가 완료되지 않는다
        WebSocketSession session = mock(WebSocketSession.class);
        given(session.close(any())).willReturn(Mono.never());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.POLICY_VIOLATION);

        // when·then: 상한을 넘기면 스스로 끝나야 한다. 안 그러면 firstWithSignal이 안 끝나
        // doFinally(cleanup)까지 막혀 세션 명부·방 구독·Redis 항목이 통째로 남는다.
        // verify()에 상한을 준다 — 기본값은 무한 대기라, 누가 .timeout(...)을 지우면 이 테스트가
        // 실패하는 대신 CI를 멈춰 세운다(지키려던 회귀 신호가 사라진다).
        StepVerifier.withVirtualTime(() -> handler.closeWithReason(session, "s1"))
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("PII는 채팅 메시지 경로로만 나간다 — 핸들러가 만드는 응답엔 이메일·원문이 실리지 않는다")
    void handler_built_messages_carry_no_pii() {
        // given: 발신자 정보를 다 갖춘 저장 결과(= PII가 흘러들 여지가 가장 큰 입력)
        ChatMessageView saved = new ChatMessageView(
                "65a1f2c3d4e5f60718293a4b", roomId, UUID.randomUUID(), "닉네임",
                "sender@example.com", ChatRole.BUYER.name(), "NORMAL",
                "원본", "원본", "c1", Instant.parse("2026-06-11T00:00:00Z"));

        // when & then: ACK·ROOM_INFO는 발신자 식별정보를 싣지 않는다.
        // (이메일·원문은 방주인 게이팅을 통과한 NORMAL/NOTICE에만 붙는다 — 다른 경로로 새면 게이트가 무의미해진다)
        assertThat(handler.toAck(saved, "c1"))
                .satisfies(m -> {
                    assertThat(m.senderEmail()).isNull();
                    assertThat(m.originalMessage()).isNull();
                    assertThat(m.displayMessage()).isNull();
                });
        assertThat(handler.roomInfo(3L, true))
                .satisfies(m -> {
                    assertThat(m.senderEmail()).isNull();
                    assertThat(m.originalMessage()).isNull();
                });
    }

    @Test
    @DisplayName("⭐ 토큰 없는 핸드셰이크도 flush 상한을 받는다 — 이 셋이 상한 없이 남으면 위조 토큰이 fd 를 묶는다")
    void close_without_a_token_is_also_bounded() {
        // given: 서브프로토콜에 토큰이 없는 핸드셰이크. 운영 경로(handle)로 들어가 등록 전에 닫히는
        // 세 자리 중 하나다 — 깨진 핸드셰이크 · 토큰 부재 · 검증 실패. 이 셋은 세션이 등록되기
        // 전이라 사유 조회에 쓸 sid 가 없어 상수 1008 로 닫는다.
        //
        // ⚠️ 이 칸이 없으면 closeBounded 의 상한을 지워도 전 스위트가 초록이다(실측: 테스트 트리
        // 전수 grep 에서 그 이름이 0건이었다).
        ReactorNettyWebSocketSession session = mock(ReactorNettyWebSocketSession.class);
        ChannelId channelId = DefaultChannelId.newInstance();
        given(session.getHandshakeInfo()).willReturn(new HandshakeInfo(
                URI.create("wss://x/ws/chat?roomId=" + UUID.randomUUID()),
                new HttpHeaders(), Mono.empty(), null));
        given(session.close(any())).willReturn(Mono.never());
        given(session.getChannelId()).willReturn(channelId);

        // when
        StepVerifier.withVirtualTime(() -> handler.handle(session))
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // then
        then(connections).should(times(1)).dispose(channelId);
    }

    @Test
    @DisplayName("⭐ 거부 통보도 flush 상한을 받는다 — 상한이 없으면 적대적 클라가 fd 를 묶는다")
    void deny_and_close_timeout_disposes_connection() {
        // given: WS 업그레이드는 끝났고 수신 윈도를 0 으로 광고하는 클라. send 도 close 도 완료되지 않는다.
        // 이 경로가 TOO_MANY_SESSIONS · KICKED · BANNED · 입장 시 ROOM_ENDED · 위조 토큰이 가는 길,
        // 즉 적대적이거나 깨진 클라가 실제로 모는 길이다. 형제(closeWithReason)는 이 상한을 이미 갖는다.
        ReactorNettyWebSocketSession session = mock(ReactorNettyWebSocketSession.class);
        ChannelId channelId = DefaultChannelId.newInstance();
        given(session.textMessage(anyString())).willReturn(mock(WebSocketMessage.class));
        given(session.send(any())).willReturn(Mono.never());
        // close 도 세운다 — 안 세우면 목이 null 을 돌려주고 `.then(null)` 이 NPE 로 터져,
        // "상한이 없다" 와 "하네스가 모자라다" 가 같은 빨강이 된다(실측으로 그 빨강을 한 번 받았다).
        given(session.close(any())).willReturn(Mono.never());
        given(session.getChannelId()).willReturn(channelId);

        // when
        StepVerifier.withVirtualTime(() ->
                        handler.denyAndClose(session, EntryDeniedException.Reason.TOO_MANY_SESSIONS))
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // then: close 는 write future 에 채널 닫기를 걸어두는 구조라, 그 write 가 flush 되지 않으면
        // 채널이 남는다 — 그리고 "이미 close 보냄" 표시 때문에 다시 닫으려 해도 무동작이다.
        then(connections).should(times(1)).dispose(channelId);
    }

    @Test
    @DisplayName("종료 — close가 끝내 나가지 못하면 소켓을 직접 내린다(커넥션 누수 차단)")
    void close_timeout_disposes_connection() {
        // given: 소켓을 읽지 않는 클라. close write future가 완료되지 않는다.
        ReactorNettyWebSocketSession session = mock(ReactorNettyWebSocketSession.class);
        ChannelId channelId = DefaultChannelId.newInstance();
        given(session.close(any())).willReturn(Mono.never());
        given(session.getChannelId()).willReturn(channelId);
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.POLICY_VIOLATION);

        // when
        StepVerifier.withVirtualTime(() -> handler.closeWithReason(session, "s1"))
                .thenAwait(Duration.ofSeconds(10))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        // then: 앱 자원만 정리하고 채널을 남기면 파일 디스크립터가 쌓인다
        then(connections).should(times(1)).dispose(channelId);
    }

    @Test
    @DisplayName("종료 — close가 정상적으로 나가면 강제 회수까지 가지 않는다")
    void close_success_does_not_dispose() {
        // given
        ReactorNettyWebSocketSession session = mock(ReactorNettyWebSocketSession.class);
        given(session.close(any())).willReturn(Mono.empty());
        given(registry.closeStatusOf("s1")).willReturn(CloseStatus.NORMAL);

        // when
        StepVerifier.create(handler.closeWithReason(session, "s1")).expectComplete().verify(VERIFY_CEILING);

        // then
        then(connections).should(never()).dispose(any());
    }

    @Test
    @DisplayName("모르는 필드가 실려 와도 파싱은 성공한다 — 여기서 실패하면 메시지가 아니라 연결이 끊긴다")
    void inbound_ignores_unknown_fields() {
        // given: 계약 3필드 + 클라가 얹은 자기 필드
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.empty());
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\",\"myField\":1}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 커맨드까지 도달했다 = 파싱이 통과했다. 거부 카운터도 오르지 않는다.
        then(sendUseCase).should(times(1)).send(any());
        then(registry).should(never()).terminateKicked(anyString());
    }

    @Test
    @DisplayName("인바운드 상한을 넘은 프레임은 파싱·응답 없이 버리고 지표만 남긴다")
    void inbound_over_cap_is_dropped_silently() {
        // given: 상한을 넘은 세션. 프레임 자체는 정상이라, 버리지 않으면 전송까지 간다
        io.micrometer.core.instrument.MeterRegistry meters =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ChatWebSocketHandler metered = new ChatWebSocketHandler(
                mock(RoomTokenVerifier.class), entryGate, registry, sendUseCase, subscriber, connections,
                systemMessageService, new SessionTimeouts(Duration.ofHours(1), PING_INTERVAL, LIVENESS_DEADLINE),
                new com.sapari.chat.infrastructure.metrics.MicrometerChatMetrics(meters));
        given(registry.shouldDropInbound("s1")).willReturn(true);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(metered.runInbound(Flux.just(
                        text("{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}")), "s1", session))
                .expectComplete().verify(VERIFY_CEILING);

        // then: 아무것도 돌려주지 않는다 — 거부 응답을 주면 그 응답이 다시 비용이 된다
        then(sendUseCase).should(never()).send(any());
        then(entryGate).should(never()).isRoomAlive(anyString(), any());
        then(registry).should(never()).shouldReplyToRejection(anyString());
        then(registry).should(never()).sendToSession(anyString(), any());
        assertThat(meters.get("chat.inbound.dropped").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("인바운드 상한은 파싱보다 앞이다 — 상한을 넘은 깨진 프레임은 형식 거부 경로에도 닿지 않는다")
    void inbound_cap_runs_before_parse() {
        // given: 파싱하면 형식 거부(솎기 경로)로 가는 프레임
        given(registry.shouldDropInbound("s1")).willReturn(true);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(handler.runInbound(Flux.just(text("{not json")), "s1", session))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        then(registry).should(never()).shouldReplyToRejection(anyString());
    }

    @Test
    @DisplayName("인바운드 상한은 디코드보다 앞이다 — 버린 프레임은 문자열로 읽히지 않는다")
    void inbound_cap_runs_before_decode() {
        // given
        given(registry.shouldDropInbound("s1")).willReturn(true);
        WebSocketMessage frame = mock(WebSocketMessage.class);
        given(frame.getType()).willReturn(WebSocketMessage.Type.TEXT);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(handler.runInbound(Flux.just(frame), "s1", session))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        then(frame).should(never()).getPayloadAsText();
    }

    @Test
    @DisplayName("파싱은 됐지만 커맨드에서 거부된 프레임도 솎기 경로를 탄다 — 응답 비용은 거부 사유를 가리지 않는다")
    void inbound_routes_command_rejection_through_throttle() {
        // given: {}는 파싱에 성공하고 커맨드 생성에서 떨어진다
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(handler.onInbound("s1", session, "{}")).expectComplete().verify(VERIFY_CEILING);

        // then
        then(registry).should(times(1)).shouldReplyToRejection("s1");
    }

    @Test
    @DisplayName("서버 실패(INTERNAL)도 사유 그대로 돌려준다 — 첫 거부는 반드시 답한다")
    void internal_error_is_answered_with_its_code() {
        // given: 저장이 터지는 상황(Mongo·Redis 장애)
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new RuntimeException("mongo down")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 서버 잘못이라고 세거나 끊지 않는다 — 사유만 돌려준다
        then(registry).should(never()).terminateKicked(anyString());
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        then(registry).should(times(1)).sendToSession(eq("s1"), sent.capture());
        assertThat(sent.getValue().code()).isEqualTo("INTERNAL");
    }

    @Test
    @DisplayName("방 종료 재확인을 못 읽으면 ERROR(UNAVAILABLE) 만 돌려주고 세션은 유지한다 — 종료로 읽지 않는다")
    void unreadable_room_alive_is_answered_without_closing() {
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(entryGate.isRoomAlive(anyString(), any()))
                .willReturn(Mono.error(new ChatSendUnavailableException(new RuntimeException("redis down"))));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then
        then(sendUseCase).should(never()).send(any());
        then(registry).should(never()).terminateRoomEnded(anyString());
        then(registry).should(never()).terminateKicked(anyString());
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        then(registry).should(times(1)).sendToSession(eq("s1"), sent.capture());
        assertThat(sent.getValue().code()).isEqualTo("UNAVAILABLE");
        assertThat(sent.getValue().clientMsgId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("거부 응답이 솎일 차례면 아무것도 보내지 않는다 — 되돌림 비용이 곧 공격 표면이다")
    void rejection_is_dropped_when_throttled() {
        // given
        given(registry.shouldReplyToRejection("s1")).willReturn(false);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new IllegalArgumentException("bad")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then
        then(registry).should(never()).sendToSession(anyString(), any());
    }

    @Test
    @DisplayName("전송 경로에서 강퇴가 확인되면 세션을 끊는다 — 그 Pod가 강퇴 신호를 놓쳤다는 뜻이다")
    void kicked_on_send_terminates_session() {
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new UserKickedException("kicked")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 안 닫으면 계속 읽으면서 프레임마다 강퇴 조회를 태운다
        then(registry).should(times(1)).terminateKicked("s1");
    }

    @Test
    @DisplayName("종료된 방으로 확인되면 전송이 NOT_ACTIVE로 거부된다 — 신호를 놓친 Pod가 이력을 쌓지 않게")
    void inbound_rejects_when_room_recheck_says_ended() {
        // given: 재확인 창이 열려 마커를 읽었고, 그 방은 이미 끝났다
        given(entryGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(false));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new LiveNotActiveException("ended")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 커맨드에 실려 나간 값이 false여야 서비스 1단계가 거부한다
        then(sendUseCase).should(times(1)).send(argThat(c -> !c.isRoomAlive()));
        then(registry).should(times(1)).sendToSession(eq("s1"), argThat(m -> "NOT_ACTIVE".equals(m.code())));
    }

    /**
     * 와이어 순서를 묶는다 — <b>조립 순서를 실행 순서로 읽던 단언을 고친다.</b>
     *
     * <p>Reactor 에서 {@code a.then(b)} 의 {@code b} 는 <b>조립 시점</b>에 평가된다.
     * {@code sendToSession} 과 {@code renderToSession} 은 둘 다 조립 중에 목이 기록하는 즉시
     * 호출이고 소스 순서대로 들어간다 — 즉 {@code InOrder} 로 그 둘을 보면 <b>선언 순서</b>를
     * 고정하는 것이고 전달 순서는 고정하지 않는다. 생존하던 변이는 이렇다: 둘을 현재 순서로
     * 지역변수에 올린 뒤 {@code sys.then(err)} 로 거꾸로 구독한다 — 조립 순서가 그대로라
     * {@code InOrder} 는 초록인데 {@code ERROR} 와 {@code SYSTEM(ROOM_ENDED)} 의 와이어 순서가
     * 뒤집힌다.
     *
     * <p>그게 왜 하중인가. {@code ERROR} 가 낙관적 버블 롤백에 필요한 {@code clientMsgId} 를 싣는
     * <b>유일한</b> 프레임이고, 순서가 뒤집히면 sink 가 먼저 닫혀 뒤엣것이 사라진다.
     *
     * <p>그래서 스텁이 {@code Mono.fromRunnable} 로 <b>실행</b>을 공유 리스트에 기록한다.
     * {@code terminateRoomEnded} 는 {@code doFinally} 람다 안이라 그 짝만은 원래부터 진짜 실행
     * 순서였다 — 그 단언은 그대로 둔다. 형제
     * {@code kicked_on_send_answers_before_terminating} 도 같은 이유로 건전하다.
     */
    @Test
    @DisplayName("방 종료 확인 — ERROR 가 SYSTEM 보다 먼저 와이어에 나간다(조립 순서가 아니라 실행 순서로 잰다)")
    void roomEnded_onSend_mirrorsNormalEndPath() {
        // given: 이 Pod가 종료 신호를 놓쳤고 게이트가 마커로 방금 확인한 상황
        List<String> wireOrder = new CopyOnWriteArrayList<>();
        given(entryGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(false));
        given(registry.sendToSession(anyString(), any()))
                .willReturn(Mono.fromRunnable(() -> wireOrder.add("ERROR")));
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(systemMessageService.renderToSession("s1", SystemMessageCode.ROOM_ENDED))
                .willReturn(Mono.fromRunnable(() -> wireOrder.add("SYSTEM")));
        given(sendUseCase.send(any())).willReturn(Mono.error(new LiveNotActiveException("ended")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: ERROR(롤백 키) → SYSTEM(종료 사유) → 종료. 순서가 뒤집히면 sink가 닫혀 뒤엣것이 사라진다.
        // ⭐ 실행 순서다 — InOrder 로 이 둘을 보면 조립 순서를 보는 것이고 hoist-and-swap 이 생존한다.
        assertThat(wireOrder)
                .as("ERROR 가 SYSTEM 보다 먼저 나가지 않았다 — 롤백 키를 실은 유일한 프레임이 뒤로 밀렸다")
                .containsExactly("ERROR", "SYSTEM");
        // 프레임의 내용은 따로 본다 — 순서 단언이 내용까지 덮지는 않는다.
        then(registry).should(times(1)).sendToSession(eq("s1"),
                argThat(m -> "NOT_ACTIVE".equals(m.code()) && "c1".equals(m.clientMsgId())));
        // terminateRoomEnded 만은 doFinally 람다 안이라 원래부터 진짜 실행 순서였다.
        InOrder order = inOrder(registry, systemMessageService);
        order.verify(systemMessageService).renderToSession("s1", SystemMessageCode.ROOM_ENDED);
        order.verify(registry).terminateRoomEnded("s1");
    }

    @Test
    @DisplayName("방 종료 사유도 솎기를 거치지 않는다 — 세션이 끝나 반복될 수 없으니 증폭이 없다")
    void roomEnded_bypassesRejectionThrottle() {
        // given: 직전 거부로 솎기 창이 닫혀 있는 상황
        given(entryGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(false));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(registry.shouldReplyToRejection("s1")).willReturn(false);
        given(systemMessageService.renderToSession("s1", SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willReturn(Mono.error(new LiveNotActiveException("ended")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then
        then(registry).should(times(1)).sendToSession(eq("s1"), argThat(m -> "NOT_ACTIVE".equals(m.code())));
        then(registry).should(times(1)).terminateRoomEnded("s1");
    }

    @Test
    @DisplayName("강퇴 — 사유를 먼저 보내고 그 다음에 끊는다(뒤집으면 sink가 닫혀 사유가 조용히 사라진다)")
    void kicked_on_send_answers_before_terminating() {
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new UserKickedException("kicked")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: terminate가 먼저 나가면 tryEmitNext가 FAIL_TERMINATED가 되어 클라는 1008만 받고 사유를 모른다
        InOrder order = inOrder(registry);
        order.verify(registry).sendToSession(eq("s1"), argThat(m ->
                "ERROR".equals(m.type()) && "KICKED".equals(m.code()) && "c1".equals(m.clientMsgId())));
        order.verify(registry).terminateKicked("s1");
    }

    @Test
    @DisplayName("강퇴 사유는 솎기를 거치지 않는다 — 세션이 그 자리에서 끝나 반복될 수 없으니 증폭이 없다")
    void kicked_reply_bypasses_rejection_throttle() {
        // given: 직전에 다른 거부가 있어 솎기 창이 닫혀 있는 상황
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(registry.shouldReplyToRejection("s1")).willReturn(false);
        given(sendUseCase.send(any())).willReturn(Mono.error(new UserKickedException("kicked")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 창이 닫혀 있어도 사유는 나가고, 세션도 끊긴다
        then(registry).should(times(1)).sendToSession(eq("s1"), argThat(m -> "KICKED".equals(m.code())));
        then(registry).should(times(1)).terminateKicked("s1");
    }

    @Test
    @DisplayName("레이트리밋은 세션을 끊지 않고 로컬 창만 기억한다 — 빠르게 치는 정상 사용자를 끊으면 안 된다")
    void rate_limit_records_window_without_terminating() {
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(sendUseCase.send(any())).willReturn(Mono.error(new ChatRateLimitException("x", 3)));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 끊지 않고, 대신 다음 프레임이 Redis에 닿지 않도록 창을 기억한다
        then(registry).should(never()).terminateKicked(anyString());
        then(registry).should(times(1)).recordRateLimited("s1", 3L);
    }

    @Test
    @DisplayName("레이트리밋 창 안이면 커맨드까지 가지 않는다 — 거부 비용이 Redis 왕복 3회다")
    void inbound_short_circuits_within_rate_limit_window() {
        // given: 이미 제한이 걸려 있고 2초 남았다
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(2L);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: 서비스를 아예 부르지 않고, 남은 시간을 그대로 돌려준다
        then(sendUseCase).should(never()).send(any());
        ArgumentCaptor<OutboundMessage> sent = ArgumentCaptor.forClass(OutboundMessage.class);
        then(registry).should(times(1)).sendToSession(eq("s1"), sent.capture());
        assertThat(sent.getValue().type()).isEqualTo("RATE_LIMIT");
        assertThat(sent.getValue().retryAfterSeconds()).isEqualTo(2L);
    }

    @Test
    @DisplayName("인바운드는 한 번에 한 건씩 처리된다 — 동시에 들어가면 레이트리밋 로컬 창이 무력해진다")
    void runInbound_processesOneAtATime() {
        // given: 전송이 끝나기 전에 다음 프레임이 들어오는지 세어본다. flatMap이면 기본 동시성 256이라
        // 한 클라가 파이프라이닝하는 것만으로 창이 무장되기 전에 전부 Redis를 다녀온다.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        given(registry.rateLimitRetryAfterSeconds(anyString())).willReturn(0L);
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willAnswer(inv -> Mono.fromCallable(() -> {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    return view("id");
                })
                .delayElement(Duration.ofMillis(20))
                .doOnNext(v -> inFlight.decrementAndGet()));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        Flux<WebSocketMessage> payloads = Flux.range(1, 5)
                .map(i -> text("{\"type\":\"NORMAL\",\"content\":\"m" + i + "\",\"clientMsgId\":\"c" + i + "\"}"));

        // when
        StepVerifier.create(handler.runInbound(payloads, "s1", session))
                .expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(maxInFlight.get()).isOne();
        then(sendUseCase).should(times(5)).send(any());
    }

    @Test
    @DisplayName("인바운드 처리 순서가 전송 순서와 같다 — 어긋나면 저장·중계 순서가 화면과 달라진다")
    void runInbound_preservesOrder() {
        // given
        List<String> seen = new ArrayList<>();
        given(registry.rateLimitRetryAfterSeconds(anyString())).willReturn(0L);
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willAnswer(inv -> {
            SendChatCommand c = inv.getArgument(0);
            // 앞 프레임일수록 오래 걸리게 만든다 — 순서를 안 지키면 뒤엣것이 먼저 끝난다
            long delay = "m1".equals(c.content()) ? 40 : 5;
            return Mono.delay(Duration.ofMillis(delay))
                    .doOnNext(t -> seen.add(c.content()))
                    .thenReturn(view("id"));
        });
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        Flux<WebSocketMessage> payloads = Flux.just(
                text("{\"type\":\"NORMAL\",\"content\":\"m1\",\"clientMsgId\":\"c1\"}"),
                text("{\"type\":\"NORMAL\",\"content\":\"m2\",\"clientMsgId\":\"c2\"}"),
                text("{\"type\":\"NORMAL\",\"content\":\"m3\",\"clientMsgId\":\"c3\"}"));

        // when
        StepVerifier.create(handler.runInbound(payloads, "s1", session)).expectComplete().verify(VERIFY_CEILING);

        // then
        assertThat(seen).containsExactly("m1", "m2", "m3");
    }

    @Test
    @DisplayName("에코 절삭이 이모지를 반으로 갈라도 프레임은 나간다 — 여기서 터지면 그 세션 아웃바운드가 죽는다")
    void truncatedSurrogate_stillSerializes() {
        // given: 64번째 char가 상위 서로게이트라 substring(0,64)가 짝을 쪼갠다.
        // 쪼개진 문자가 직렬화에서 예외를 내면 onInbound가 아니라 아웃바운드 Flux가 죽어 세션이 멎는다.
        String id = "x".repeat(63) + "\uD83D\uDE00";
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.shouldReplyToRejection("s1")).willReturn(true);
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"" + id + "\"}";
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);
        // 실제 와이어로 나가는 직렬화까지 태운다
        then(registry).should(times(1)).sendToSession(eq("s1"), argThat(m -> {
            assertThat(handler.serialize(m)).isNotBlank();
            return true;
        }));
    }

    private ChatMessageView view(String id) {
        return new ChatMessageView(id, roomId, userId, "닉", null, "BUYER", "NORMAL",
                "hi", null, "c1", Instant.parse("2026-06-11T00:00:00Z"));
    }

    private static WebSocketMessage text(String payload) {
        return new WebSocketMessage(WebSocketMessage.Type.TEXT,
                org.springframework.core.io.buffer.DefaultDataBufferFactory.sharedInstance
                        .wrap(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
