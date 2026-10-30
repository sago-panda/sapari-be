package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;

import com.sapari.chat.application.handler.ChatBroadcastSubscriber;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.application.service.SystemMessageService;
import com.sapari.chat.application.protocol.SystemMessageCode;
import com.sapari.chat.command.SendChatCommand;
import com.sapari.chat.domain.exception.ChatPermissionDeniedException;
import com.sapari.chat.domain.exception.ChatRateLimitException;
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


    /** StepVerifier 종결자 상한 — 없으면 회귀가 테스트를 실패시키는 대신 <b>멈추게</b> 한다(근거: modules/chat/AGENTS.md). */
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
                new SessionTimeouts(lifetime, PING_INTERVAL, LIVENESS_DEADLINE));
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
        given(registry.outbound("s1")).willReturn(Flux.never());
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
        given(registry.outbound("s1")).willReturn(Flux.never());
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
        given(registry.outbound("s1")).willReturn(Flux.never());
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
        given(registry.outbound("s1")).willReturn(Flux.never());
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
        given(registry.outbound("s1")).willReturn(Flux.never());
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
        OutboundMessage info = handler.roomInfoFor(roomId, session).block();

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

    @Test
    @DisplayName("방 종료 확인 — 정상 종료 경로와 같은 프레임·같은 코드로 닫는다(프론트가 구분할 필요 없게)")
    void roomEnded_onSend_mirrorsNormalEndPath() {
        // given: 이 Pod가 종료 신호를 놓쳤고 게이트가 마커로 방금 확인한 상황
        given(entryGate.isRoomAlive(anyString(), any())).willReturn(Mono.just(false));
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.rateLimitRetryAfterSeconds("s1")).willReturn(0L);
        given(systemMessageService.renderToSession("s1", SystemMessageCode.ROOM_ENDED)).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willReturn(Mono.error(new LiveNotActiveException("ended")));
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\"}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).expectComplete().verify(VERIFY_CEILING);

        // then: ERROR(롤백 키) → SYSTEM(종료 사유) → 종료. 순서가 뒤집히면 sink가 닫혀 뒤엣것이 사라진다.
        InOrder order = inOrder(registry, systemMessageService);
        order.verify(registry).sendToSession(eq("s1"),
                argThat(m -> "NOT_ACTIVE".equals(m.code()) && "c1".equals(m.clientMsgId())));
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
        Flux<String> payloads = Flux.range(1, 5)
                .map(i -> "{\"type\":\"NORMAL\",\"content\":\"m" + i + "\",\"clientMsgId\":\"c" + i + "\"}");

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
        Flux<String> payloads = Flux.just(
                "{\"type\":\"NORMAL\",\"content\":\"m1\",\"clientMsgId\":\"c1\"}",
                "{\"type\":\"NORMAL\",\"content\":\"m2\",\"clientMsgId\":\"c2\"}",
                "{\"type\":\"NORMAL\",\"content\":\"m3\",\"clientMsgId\":\"c3\"}");

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
}
