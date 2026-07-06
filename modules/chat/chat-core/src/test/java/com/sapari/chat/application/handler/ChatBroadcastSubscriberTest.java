package com.sapari.chat.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sapari.chat.application.port.ChatBroadcaster;
import com.sapari.chat.application.port.ChatSessionManager;
import com.sapari.chat.application.protocol.ChatEnvelope;
import com.sapari.chat.application.protocol.OutboundMessage;
import com.sapari.chat.domain.model.ChatMessage;
import com.sapari.chat.domain.model.ChatMessageType;
import com.sapari.chat.domain.model.ChatRole;
import com.sapari.chat.domain.model.ChatSession;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ChatBroadcastSubscriberTest {

    private ChatSessionManager sessionManager;
    private ChatBroadcastSubscriber subscriber;

    private final UUID roomId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID sellerId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID buyerId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @BeforeEach
    void setUp() {
        sessionManager = mock(ChatSessionManager.class);
        subscriber = new ChatBroadcastSubscriber(mock(ChatBroadcaster.class), sessionManager);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Function<ChatSession, OutboundMessage>> resolverCaptor() {
        return ArgumentCaptor.forClass(Function.class);
    }

    private ChatSession owner() {
        return new ChatSession(roomId, sellerId, ChatRole.SELLER, "셀러", "seller@example.com", true);
    }

    private ChatSession viewer(UUID userId) {
        return new ChatSession(roomId, userId, ChatRole.BUYER, "구매자", "buyer@example.com", false);
    }

    @Test
    @DisplayName("CHAT — 방주인 세션엔 email·원문 포함(toOwnerView), 일반 세션엔 마스킹만(toView)")
    void chat_fanout_pii_gated_per_session() {
        when(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).thenReturn(Mono.empty());
        ChatMessage message = new ChatMessage(
                "65a1f2c3d4e5f60718293a4b", roomId, buyerId, "구매자", "buyer@example.com",
                ChatRole.BUYER, new ChatMessageType.Normal(),
                "원본바보",          // originalMessage
                "원본**",           // displayMessage (마스킹)
                null, Instant.parse("2026-06-11T00:00:00Z"));

        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.ChatMsg(message))).verifyComplete();

        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        verify(sessionManager).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        OutboundMessage ownerMsg = resolver.apply(owner());
        assertThat(ownerMsg.type()).isEqualTo("NORMAL");
        assertThat(ownerMsg.senderEmail()).isEqualTo("buyer@example.com");
        assertThat(ownerMsg.originalMessage()).isEqualTo("원본바보");
        assertThat(ownerMsg.displayMessage()).isEqualTo("원본**");

        OutboundMessage viewerMsg = resolver.apply(viewer(buyerId));
        assertThat(viewerMsg.senderEmail()).isNull();          // PII 제외
        assertThat(viewerMsg.originalMessage()).isNull();      // 원문 제외
        assertThat(viewerMsg.displayMessage()).isEqualTo("원본**");   // 마스킹 본문은 유지
    }

    @Test
    @DisplayName("KICK_EVENT — 당사자엔 SYSTEM(KICKED), 그 외엔 KICK(userId) + 당사자 세션 close")
    void kick_fanout_and_close() {
        UUID kickedId = buyerId;
        UUID otherId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(sessionManager.sendToRoomGated(eq(roomId), any(Function.class))).thenReturn(Mono.empty());
        when(sessionManager.closeUser(roomId, kickedId)).thenReturn(Mono.empty());

        StepVerifier.create(subscriber.route(roomId, new ChatEnvelope.KickEvent(kickedId))).verifyComplete();

        verify(sessionManager).closeUser(roomId, kickedId);     // 당사자 세션 close

        ArgumentCaptor<Function<ChatSession, OutboundMessage>> cap = resolverCaptor();
        verify(sessionManager).sendToRoomGated(eq(roomId), cap.capture());
        Function<ChatSession, OutboundMessage> resolver = cap.getValue();

        OutboundMessage selfMsg = resolver.apply(viewer(kickedId));
        assertThat(selfMsg.type()).isEqualTo("SYSTEM");
        assertThat(selfMsg.code()).isEqualTo("KICKED");

        OutboundMessage otherMsg = resolver.apply(viewer(otherId));
        assertThat(otherMsg.type()).isEqualTo("KICK");
        assertThat(otherMsg.userId()).isEqualTo(kickedId);
    }
}
