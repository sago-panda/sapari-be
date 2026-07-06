package com.sapari.streamingapp.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

class ChatWebSocketHandlerTest {

    private ChatBroadcastSubscriber subscriber;
    private ChatWebSocketHandler handler;

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID userId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        subscriber = mock(ChatBroadcastSubscriber.class);
        handler = new ChatWebSocketHandler(
                mock(RoomTokenVerifier.class), mock(EntryGate.class),
                mock(ChatSessionRegistry.class), mock(SendChatUseCase.class), subscriber);
    }

    @Test
    @DisplayName("buildCommand — 서버신뢰값은 세션에서, 클라값은 InboundMessage에서")
    void build_command_trusts_session_for_server_fields() {
        ChatSession session = new ChatSession(roomId, userId, ChatRole.SELLER, "셀러", "s@example.com", true);
        InboundMessage in = new InboundMessage("NOTICE", "공지입니다", "cmid-1");

        SendChatCommand c = handler.buildCommand(session, in);

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
        ChatMessageView view = new ChatMessageView(
                "65a1f2c3d4e5f60718293a4b", roomId, userId, "닉", null, "BUYER", "NORMAL",
                "hi", null, "cmid-9", Instant.parse("2026-06-11T00:00:00Z"));

        OutboundMessage ack = handler.toAck(view, "cmid-9");

        assertThat(ack.type()).isEqualTo("ACK");
        assertThat(ack.id()).isEqualTo("65a1f2c3d4e5f60718293a4b");
        assertThat(ack.clientMsgId()).isEqualTo("cmid-9");
        assertThat(ack.createdAt()).isEqualTo(Instant.parse("2026-06-11T00:00:00Z"));
    }

    @Test
    @DisplayName("toError — 레이트리밋은 RATE_LIMIT+retryAfter, 그 외는 ERROR+code, 모두 clientMsgId 운반")
    void to_error_maps_exceptions() {
        OutboundMessage rl = handler.toError(new ChatRateLimitException("x", 3), "c");
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
        OutboundMessage ri = handler.roomInfo(5L, true);

        assertThat(ri.type()).isEqualTo("ROOM_INFO");
        assertThat(ri.activeCount()).isEqualTo(5L);
        assertThat(ri.isRoomOwner()).isTrue();
    }

    @Test
    @DisplayName("구독 ref-count — 같은 방 다중 입장은 구독 1개 공유, 마지막 퇴장에만 dispose")
    void room_subscription_ref_counted() {
        Disposable disposable = mock(Disposable.class);
        when(subscriber.subscribeRoom(roomId)).thenReturn(disposable);

        handler.acquireRoom(roomId);
        handler.acquireRoom(roomId);
        verify(subscriber, times(1)).subscribeRoom(roomId);   // 공유 — 구독은 1회만
        assertThat(handler.isSubscribed(roomId)).isTrue();

        handler.releaseRoom(roomId);
        verify(disposable, never()).dispose();                // 아직 1명 남음
        assertThat(handler.isSubscribed(roomId)).isTrue();

        handler.releaseRoom(roomId);
        verify(disposable).dispose();                         // 마지막 퇴장 → 해제
        assertThat(handler.isSubscribed(roomId)).isFalse();
    }
}
