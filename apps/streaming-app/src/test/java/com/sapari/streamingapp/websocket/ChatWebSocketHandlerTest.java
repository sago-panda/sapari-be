package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import io.netty.channel.ChannelId;
import io.netty.channel.DefaultChannelId;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatWebSocketHandlerTest {

    private ChatBroadcastSubscriber subscriber;
    private ChatSessionRegistry registry;
    private SendChatUseCase sendUseCase;
    private ChatWebSocketHandler handler;

    private NettyConnectionRegistry connections;

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        subscriber = mock(ChatBroadcastSubscriber.class);
        registry = mock(ChatSessionRegistry.class);
        sendUseCase = mock(SendChatUseCase.class);
        connections = mock(NettyConnectionRegistry.class);
        handler = new ChatWebSocketHandler(
                mock(RoomTokenVerifier.class), mock(EntryGate.class),
                registry, sendUseCase, subscriber, connections);
    }

    @Test
    @DisplayName("buildCommand — 서버신뢰값은 세션에서, 클라값은 InboundMessage에서")
    void build_command_trusts_session_for_server_fields() {
        // given
        ChatSession session = new ChatSession(roomId, userId, ChatRole.SELLER, "셀러", "s@example.com", true);
        InboundMessage in = new InboundMessage("NOTICE", "공지입니다", "cmid-1");

        SendChatCommand c = handler.buildCommand(session, in);

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
                .verifyComplete();
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
                .verifyComplete();

        // then: 전송 파이프라인에 아예 들어가지 않는다 — Redis 강퇴 조회는 장애 시 통과(fail-open)라 믿을 수 없다
        then(sendUseCase).should(never()).send(any());
    }

    @Test
    @DisplayName("onInbound — type 누락이면 연결을 끊지 않고 ERROR(VALIDATION)만 응답한다")
    void inbound_without_type_keeps_stream_alive() {
        // given
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when: 실제 수신 배선과 같은 flatMap 모양으로 태운다
        // (커맨드 생성이 인자평가 위치에서 throw하면 여기서 스트림이 죽는다)
        StepVerifier.create(Flux.just("{\"content\":\"안녕\"}")
                        .flatMap(payload -> handler.onInbound("s1", session, payload))
                        .then())
                .verifyComplete();

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
        StepVerifier.create(handler.closeWithReason(session, "s1")).verifyComplete();

        // then
        then(connections).should(never()).dispose(any());
    }

    @Test
    @DisplayName("모르는 필드가 실려 와도 파싱은 성공한다 — 여기서 실패하면 메시지가 아니라 연결이 끊긴다")
    void inbound_ignores_unknown_fields() {
        // given: 계약 3필드 + 클라가 얹은 자기 필드
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(sendUseCase.send(any())).willReturn(Mono.empty());
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);
        String payload = "{\"type\":\"NORMAL\",\"content\":\"안녕\",\"clientMsgId\":\"c1\",\"myField\":1}";

        // when
        StepVerifier.create(handler.onInbound("s1", session, payload)).verifyComplete();

        // then: 커맨드까지 도달했다 = 파싱이 통과했다. 거부 카운터도 오르지 않는다.
        then(sendUseCase).should(times(1)).send(any());
        then(registry).should(never()).recordRejectedFrame(anyString());
    }

    @Test
    @DisplayName("파싱은 됐지만 커맨드에서 거부된 프레임도 상한에 세어진다 — 파싱만 세면 두 글자로 비켜간다")
    void inbound_counts_validation_failures_toward_limit() {
        // given: {}는 파싱에 성공하고 커맨드 생성에서 떨어진다
        given(registry.sendToSession(anyString(), any())).willReturn(Mono.empty());
        given(registry.recordRejectedFrame("s1")).willReturn(false);
        ChatSession session = new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "b@example.com", false);

        // when
        StepVerifier.create(handler.onInbound("s1", session, "{}")).verifyComplete();

        // then
        then(registry).should(times(1)).recordRejectedFrame("s1");
    }
}
